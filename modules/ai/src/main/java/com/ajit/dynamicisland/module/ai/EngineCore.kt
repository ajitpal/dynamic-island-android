package com.ajit.dynamicisland.module.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * LiteRT-LM with a deadline on every wait. Plain Kotlin (no Android) so the exact same code runs in a
 * desktop test against the real model files.
 *
 * Why: in 1.9 a load or answer that never finished blocked the engine's only worker thread, so every
 * later request (and even switching models) waited forever behind it: "Loading the model…" for good.
 * Now loading has a deadline, answering has a first-word, a between-words and a total deadline, and
 * a native call that won't return marks the core [stuck] so the app can restart the engine process.
 */
class EngineCore(private val log: (String) -> Unit = {}) {

    class Limits(
        /** Loading a model the first time also writes its "prepared" (XNNPACK) file, so allow more. */
        val loadMs: (modelBytes: Long, firstTime: Boolean) -> Long = { bytes, first ->
            val base = 60_000L + bytes / 1_000_000L * 60L      // 60 s + 60 s per GB
            if (first) base * 2 else base
        },
        val firstTokenMs: Long = 180_000L,
        val stallMs: Long = 60_000L,
        val totalMs: Long = 10 * 60_000L,
    )

    sealed class Outcome {
        data class Done(val text: String, val stats: Stats) : Outcome()
        data class Failed(val code: Int, val message: String, val stuck: Boolean = false) : Outcome()
    }

    /** [promptTokens] and [writeTps] are estimates (about 3.2 characters per token). */
    data class Stats(val loadMs: Long, val firstTokenMs: Long, val totalMs: Long, val promptTokens: Int, val unused: Double, val writeTps: Double)

    /** Called on the worker thread with what's happening: "loading", "reading", "writing". */
    fun interface Progress { fun stage(stage: String) }

    var limits = Limits()
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "llm-load").apply { isDaemon = true } }
    private var engine: Engine? = null
    private var loadedKey: String? = null
    @Volatile private var running: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var cancelled = false

    /** A native call didn't return in time: this process can't be trusted to run the model again. */
    @Volatile var stuck = false
        private set

    val loadedName: String? get() = loadedKey?.substringBefore('|')

    fun isLoaded(key: String) = engine != null && loadedKey == key

    /** Prepared-model files LiteRT-LM writes next to the cache dir, named after the model file. */
    fun preparedFiles(cacheDir: File, modelFile: File): List<File> =
        cacheDir.listFiles { f -> f.name.startsWith(modelFile.name + "_") && f.name.endsWith(".xnnpack_cache") }?.toList() ?: emptyList()

    /**
     * Load (or reuse) a model. [key] identifies model + backend. Throws [TimeoutException] when the
     * native load doesn't return within the limit (the core is then [stuck]).
     */
    fun load(key: String, modelFile: File, cacheDir: File, gpu: Boolean, contextTokens: Int, threads: Int?): Long {
        if (isLoaded(key)) return 0
        release()
        val first = preparedFiles(cacheDir, modelFile).isEmpty()
        val limit = limits.loadMs(modelFile.length(), first)
        log("load start key=$key first=$first limit=${limit / 1000}s file=${modelFile.length()}B")
        val t0 = System.currentTimeMillis()
        val f = loader.submit<Engine> {
            Engine(EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = if (gpu) Backend.GPU() else Backend.CPU(threads, null),
                maxNumTokens = contextTokens,
                cacheDir = cacheDir.absolutePath,
            )).also { it.initialize() }
        }
        val e = try { f.get(limit, TimeUnit.MILLISECONDS) } catch (t: TimeoutException) {
            stuck = true
            log("load TIMEOUT after ${(System.currentTimeMillis() - t0) / 1000}s")
            throw t
        } catch (t: java.util.concurrent.ExecutionException) {
            log("load FAILED: ${t.cause}")
            throw t.cause ?: t
        }
        engine = e; loadedKey = key
        val ms = System.currentTimeMillis() - t0
        log("load ok in ${ms}ms")
        return ms
    }

    /** Run one prompt on the loaded model. Never waits longer than [limits]. */
    fun generate(system: String, prompt: String, maxTokens: Int, temperature: Double, onPartial: (String) -> Unit, progress: Progress): Outcome {
        val eng = engine ?: return Outcome.Failed(E_FAILED, "Model isn't loaded")
        cancelled = false
        val t0 = System.currentTimeMillis()
        val conv = eng.createConversation(ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature.coerceIn(0.0, 1.5), seed = 0),
            maxOutputToken = maxTokens.coerceIn(16, 2048),
            thinkingConfig = ThinkingConfig(false),
        ))
        running = conv
        val done = CountDownLatch(1)
        val raw = StringBuilder()
        val firstAt = java.util.concurrent.atomic.AtomicLong(0)
        val lastAt = java.util.concurrent.atomic.AtomicLong(0)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        var lastPush = 0L
        progress.stage(STAGE_READING)
        try {
            conv.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    val now = System.currentTimeMillis()
                    if (firstAt.compareAndSet(0, now)) progress.stage(STAGE_WRITING)
                    lastAt.set(now)
                    synchronized(raw) {
                        val merged = Text.merge(raw.toString(), message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                        raw.setLength(0); raw.append(merged)
                    }
                    if (now - lastPush > 250) { lastPush = now; onPartial(Text.clean(synchronized(raw) { raw.toString() })) }
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { err.set(throwable); done.countDown() }
            })
            // Wait in short steps so every deadline is enforced.
            var timedOut: String? = null
            while (!done.await(500, TimeUnit.MILLISECONDS)) {
                val now = System.currentTimeMillis(); val first = firstAt.get(); val last = lastAt.get()
                timedOut = when {
                    cancelled -> "cancelled"
                    first == 0L && now - t0 > limits.firstTokenMs -> "The model didn't start answering within ${limits.firstTokenMs / 1000} s. Try a smaller model or a shorter request."
                    first > 0L && now - last > limits.stallMs -> "The model stopped answering for ${limits.stallMs / 1000} s."
                    now - t0 > limits.totalMs -> "The answer took longer than ${limits.totalMs / 60_000} minutes."
                    else -> null
                }
                if (timedOut != null) break
            }
            if (timedOut != null) {
                log("generate stop: $timedOut")
                runCatching { conv.cancelProcess() }
                // The runtime should end the request promptly once cancelled; if it doesn't, it's stuck.
                if (!done.await(10, TimeUnit.SECONDS)) { stuck = true; log("generate STUCK after cancel") }
                return if (timedOut == "cancelled") Outcome.Failed(E_CANCELLED, "Cancelled", stuck)
                else Outcome.Failed(E_FAILED, timedOut, stuck)
            }
        } finally {
            running = null
            if (!stuck) runCatching { conv.close() }
        }
        val out = Text.clean(synchronized(raw) { raw.toString() })
        val first = firstAt.get(); val error = err.get()
        val end = System.currentTimeMillis()
        // Rough speed: characters written per second after the first word (the runtime's own benchmark API isn't public).
        val writeSecs = if (first > 0) (end - first) / 1000.0 else 0.0
        val stats = Stats(0, if (first > 0) first - t0 else -1, end - t0, Text.estimateTokens(system + prompt), -1.0,
            if (writeSecs > 0.2) Text.estimateTokens(out) / writeSecs else -1.0)
        log("generate end chars=${out.length} first=${stats.firstTokenMs}ms total=${stats.totalMs}ms error=${error?.message}")
        return when {
            error != null && out.isEmpty() -> Outcome.Failed(E_FAILED, error.message ?: "Generation failed")
            out.isEmpty() -> Outcome.Failed(E_FAILED, "The model returned nothing. Try again.")
            else -> Outcome.Done(out, stats)
        }
    }

    /** Stop the running answer (from any thread). */
    fun cancel() { cancelled = true; runCatching { running?.cancelProcess() } }

    fun release() {
        if (stuck) return   // a stuck native engine can't be closed safely; the process will restart
        runCatching { engine?.close() }
        engine = null; loadedKey = null
    }

    companion object {
        const val STAGE_READING = "reading"
        const val STAGE_WRITING = "writing"
        // Same numbers as AiContract (kept here so this file has no Android dependency).
        const val E_FAILED = 4
        const val E_CANCELLED = 5
    }
}
