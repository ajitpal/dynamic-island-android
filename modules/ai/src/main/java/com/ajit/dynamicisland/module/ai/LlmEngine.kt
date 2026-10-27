package com.ajit.dynamicisland.module.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ajit.dynamicisland.sdk.ai.AiContract
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs one request at a time on a single worker thread. The model stays loaded for a few minutes
 * after the last request (loading takes seconds), then is released to give the memory back.
 */
object LlmEngine {
    private const val TAG = "AiEngine"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L

    interface Listener {
        fun onPartial(text: String)
        fun onDone(text: String)
        fun onError(code: Int, message: String)
    }

    class Stats(val model: String, val backend: String, val loadMs: Long, val firstTokenMs: Long, val totalMs: Long, val chars: Int)

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "llm") }
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var unload: ScheduledFuture<*>? = null

    @Volatile private var runningId: String? = null
    @Volatile private var running: Conversation? = null
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile var queued = 0
        private set
    @Volatile var lastStats: Stats? = null
        private set
    @Volatile var loadedModel: String? = null
        private set

    val busy get() = queued > 0

    fun submit(ctx: Context, id: String, system: String, prompt: String, maxTokens: Int, temperature: Float, l: Listener) {
        val app = ctx.applicationContext
        queued++
        worker.execute {
            try { run(app, id, system, prompt, maxTokens, temperature, l) }
            catch (t: Throwable) { Log.e(TAG, "request failed", t); l.onError(AiContract.E_FAILED, t.message ?: t.javaClass.simpleName) }
            finally { queued--; scheduleUnload() }
        }
    }

    fun cancel(id: String) {
        cancelled += id
        if (runningId == id) runCatching { running?.cancelProcess() }
    }

    private fun run(ctx: Context, id: String, system: String, prompt: String, maxTokens: Int, temperature: Float, l: Listener) {
        if (cancelled.remove(id)) { l.onError(AiContract.E_CANCELLED, "Cancelled"); return }
        val model = Models.active(ctx) ?: run { l.onError(AiContract.E_NO_MODEL, "No AI model downloaded yet. Open AI Engine to download one."); return }
        val max = Text.maxInputChars(model.contextTokens, maxTokens)
        if (system.length + prompt.length > max) { l.onError(AiContract.E_TOO_LONG, "Text too long for one request (max about $max characters)"); return }

        val t0 = SystemClock.elapsedRealtime()
        val eng = ensureLoaded(ctx, model)
        val loadMs = SystemClock.elapsedRealtime() - t0

        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.toDouble().coerceIn(0.0, 1.5), seed = 0),
                maxOutputToken = maxTokens.coerceIn(16, 2048),
                thinkingConfig = ThinkingConfig(false),
            )
        )
        // Qwen3's documented switch to answer without a long hidden "thinking" phase.
        val text = if (model.qwen) "$prompt /no_think" else prompt
        var raw = ""
        var lastPush = 0L
        var firstAt = 0L
        var error: Throwable? = null
        val done = CountDownLatch(1)
        runningId = id; running = conv
        try {
            conv.sendMessageAsync(text, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                    raw = Text.merge(raw, textOf(message))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPush > 250) { lastPush = now; l.onPartial(Text.clean(raw)) }
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { error = throwable; done.countDown() }
            })
            done.await()
        } finally {
            runningId = null; running = null
            runCatching { conv.close() }
        }
        val out = Text.clean(raw)
        val end = SystemClock.elapsedRealtime()
        lastStats = Stats(model.label, if (Models.useGpu(ctx)) "GPU" else "CPU", loadMs, if (firstAt > 0) firstAt - t0 - loadMs else -1, end - t0, out.length)
        when {
            cancelled.remove(id) -> l.onError(AiContract.E_CANCELLED, "Cancelled")
            error != null && out.isEmpty() -> l.onError(AiContract.E_FAILED, error?.message ?: "Generation failed")
            else -> l.onDone(out)
        }
    }

    private fun textOf(m: Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun ensureLoaded(ctx: Context, model: Model): Engine {
        unload?.cancel(false)
        val gpu = Models.useGpu(ctx)
        val key = "${model.name}:${if (gpu) "gpu" else "cpu"}"
        engine?.let { if (loadedKey == key) return it }
        release()
        fun make(gpu: Boolean) = Engine(EngineConfig(
            modelPath = Models.file(ctx, model).absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = model.contextTokens,
            cacheDir = ctx.cacheDir.absolutePath,
        )).also { it.initialize() }
        val e = try { make(gpu) } catch (t: Throwable) {
            if (!gpu) throw t
            // GPU isn't supported on every phone: fall back to the CPU and remember it.
            Log.w(TAG, "GPU failed, using CPU", t); Models.setUseGpu(ctx, false); make(false)
        }
        engine = e; loadedKey = "${model.name}:${if (Models.useGpu(ctx)) "gpu" else "cpu"}"; loadedModel = model.label
        return e
    }

    private fun scheduleUnload() {
        unload?.cancel(false)
        unload = worker.schedule({ if (queued == 0) release() }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    /** Free the model now (e.g. when it's deleted or the selection changes). */
    fun releaseAsync() { worker.execute { release() } }

    private fun release() {
        runCatching { engine?.close() }
        engine = null; loadedKey = null; loadedModel = null
    }
}
