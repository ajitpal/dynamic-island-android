package com.ajit.dynamicisland.module.ai

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.ajit.dynamicisland.sdk.ai.AiContract
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs one request at a time on a single worker thread, through [EngineCore] (deadlines on every wait).
 * The model stays loaded for a few minutes after the last request, then is released.
 *
 * Recovery: if a native call doesn't return (the core is "stuck"), every waiting request gets a clear
 * error and the AI Engine process restarts itself ([restartProcess]) so the next request starts clean.
 * A load that crashed the process last time is detected at the next start, and its prepared file is
 * deleted (a half-written prepared file could otherwise break every later load).
 */
object LlmEngine {
    private const val TAG = "AiEngine"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L

    interface Listener {
        fun onPartial(text: String)
        fun onDone(text: String)
        fun onError(code: Int, message: String)
        /** AiContract.P_* keys: queued, loading the model, reading the prompt, writing. */
        fun onProgress(p: Bundle) {}
    }

    class Stats(val model: String, val backend: String, val loadMs: Long, val firstTokenMs: Long, val totalMs: Long, val chars: Int, val writeTps: Double)

    /** A request waiting or running, and which app sent it (to explain "busy"). */
    private class Job(val id: String, val caller: String, val callerLabel: String, val listener: Listener)
    private val jobs = java.util.Collections.synchronizedList(mutableListOf<Job>())

    private fun stage(stage: String, extra: Bundle.() -> Unit = {}) = Bundle().apply { putString(AiContract.P_STAGE, stage); extra() }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "llm") }
    private val core = EngineCore { Diag.log(it) }
    private var unload: ScheduledFuture<*>? = null
    @Volatile private var runningId: String? = null
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile var queued = 0
        private set
    @Volatile var lastStats: Stats? = null
        private set
    val loadedModel: String? get() = core.loadedName

    val busy get() = queued > 0

    /** Called when the core got stuck; the app restarts the process (set by [AiEngineApp]). */
    @Volatile var restartProcess: () -> Unit = {}

    /**
     * Queue a request. [caller] is the package that sent it. With [wait] = false the request is refused
     * at once (E_BUSY) when another app's request is running or waiting.
     */
    fun submit(ctx: Context, id: String, system: String, prompt: String, maxTokens: Int, temperature: Float, l: Listener,
               caller: String = ctx.packageName, wait: Boolean = true) {
        val app = ctx.applicationContext
        if (core.stuck) { l.onError(AiContract.E_UNAVAILABLE, RESTARTING); restartProcess(); return }
        val other = synchronized(jobs) { jobs.firstOrNull { it.caller != caller } }
        if (!wait && other != null) {
            l.onError(AiContract.E_BUSY, "AI Engine is busy with ${other.callerLabel}. Try again when it's finished.")
            return
        }
        val label = try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(caller, 0)).toString() } catch (e: Exception) { caller }
        val ahead = synchronized(jobs) { jobs.size }
        val running = synchronized(jobs) { jobs.firstOrNull() }
        jobs += Job(id, caller, label, l)
        queued++
        if (ahead > 0) l.onProgress(stage(AiContract.STAGE_QUEUED) {
            putInt(AiContract.P_AHEAD, ahead); running?.let { putString(AiContract.P_BUSY_WITH, it.callerLabel) }
        })
        worker.execute {
            try { run(app, id, system, prompt, maxTokens, temperature, l) }
            catch (t: Throwable) { Log.e(TAG, "request failed", t); Diag.log("request failed: $t"); l.onError(AiContract.E_FAILED, t.message ?: t.javaClass.simpleName) }
            finally {
                queued--; synchronized(jobs) { jobs.removeAll { it.id == id } }
                if (core.stuck) failAllAndRestart() else scheduleUnload()
            }
        }
    }

    fun cancel(id: String) {
        cancelled += id
        if (runningId == id) core.cancel()
    }

    private fun run(ctx: Context, id: String, system: String, prompt: String, maxTokens: Int, temperature: Float, l: Listener) {
        if (cancelled.remove(id)) { l.onError(AiContract.E_CANCELLED, "Cancelled"); return }
        val model = Models.active(ctx) ?: run { l.onError(AiContract.E_NO_MODEL, "No AI model downloaded yet. Open AI Engine to download one."); return }
        val max = Text.maxInputChars(model.contextTokens, maxTokens)
        if (system.length + prompt.length > max) { l.onError(AiContract.E_TOO_LONG, "Text too long for one request (max about $max characters)"); return }

        unload?.cancel(false)
        val gpu = Models.useGpu(ctx)
        val key = "${model.label}|${if (gpu) "gpu" else "cpu"}"
        val modelFile = Models.file(ctx, model)
        val cacheDir = Models.preparedDir(ctx)
        var loadMs = 0L
        if (!core.isLoaded(key)) {
            val firstTime = core.preparedFiles(cacheDir, modelFile).isEmpty()
            // Preparing writes a file about the size of the model; don't start if it can't fit.
            if (firstTime && Models.freeBytes(ctx) < model.bytes + 300L * 1024 * 1024) {
                l.onError(AiContract.E_FAILED, "Not enough free storage to prepare ${model.label}: it needs about " +
                    android.text.format.Formatter.formatShortFileSize(ctx, model.bytes + 300L * 1024 * 1024) + " free. Free up space or pick a smaller model.")
                return
            }
            l.onProgress(stage(AiContract.STAGE_LOADING) { putString(AiContract.P_MODEL, model.label) })
            Diag.markLoading(ctx, model.name)
            loadMs = try {
                core.load(key, modelFile, cacheDir, gpu, model.contextTokens, null)
            } catch (t: TimeoutException) {
                // Leave the crash marker cleared: we know what happened. The prepared file may be half-written.
                Diag.clearLoading(ctx)
                core.preparedFiles(cacheDir, modelFile).forEach { it.delete() }
                Diag.record(ctx, model.label, "load timed out")
                l.onError(AiContract.E_FAILED, "${model.label} didn't finish loading. AI Engine will restart; try again, or pick a smaller model.")
                return
            } catch (t: Throwable) {
                Diag.clearLoading(ctx)
                if (gpu) { Log.w(TAG, "GPU failed, using CPU", t); Diag.log("GPU load failed ($t), switching to CPU"); Models.setUseGpu(ctx, false) }
                core.preparedFiles(cacheDir, modelFile).forEach { it.delete() }
                Diag.record(ctx, model.label, "load failed: ${t.message}")
                if (gpu) { run(ctx, id, system, prompt, maxTokens, temperature, l); return }
                l.onError(AiContract.E_FAILED, "Couldn't load ${model.label}: ${t.message ?: t.javaClass.simpleName}")
                return
            }
            Diag.clearLoading(ctx)
        }
        if (cancelled.remove(id)) { l.onError(AiContract.E_CANCELLED, "Cancelled"); return }

        // Qwen3's documented switch to answer without a long hidden "thinking" phase.
        val text = if (model.qwen) "$prompt /no_think" else prompt
        runningId = id
        val outcome = try {
            core.generate(system, text, maxTokens, temperature.toDouble(), onPartial = { l.onPartial(it) }) { st ->
                l.onProgress(stage(if (st == EngineCore.STAGE_WRITING) AiContract.STAGE_WRITING else AiContract.STAGE_READING) { putString(AiContract.P_MODEL, model.label) })
            }
        } finally { runningId = null }
        when (outcome) {
            is EngineCore.Outcome.Done -> {
                val s = outcome.stats
                lastStats = Stats(model.label, if (gpu) "GPU" else "CPU", loadMs, s.firstTokenMs, s.totalMs, outcome.text.length, s.writeTps)
                Diag.record(ctx, model.label, "ok · load ${loadMs / 1000.0}s · first word ${s.firstTokenMs / 1000.0}s · total ${s.totalMs / 1000.0}s · ~${"%.1f".format(s.writeTps)} tokens/s")
                if (cancelled.remove(id)) l.onError(AiContract.E_CANCELLED, "Cancelled") else l.onDone(outcome.text)
            }
            is EngineCore.Outcome.Failed -> {
                Diag.record(ctx, model.label, (if (outcome.stuck) "STUCK · " else "") + outcome.message)
                cancelled.remove(id)
                l.onError(outcome.code, if (outcome.stuck) "${outcome.message} AI Engine will restart; try again." else outcome.message)
            }
        }
    }

    /** The native runtime didn't return: tell everyone waiting, then restart the process. */
    private fun failAllAndRestart() {
        val waiting = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        waiting.forEach { runCatching { it.listener.onError(AiContract.E_UNAVAILABLE, RESTARTING) } }
        Diag.log("restarting AI Engine (native runtime stuck)")
        restartProcess()
    }

    private fun scheduleUnload() {
        unload?.cancel(false)
        unload = worker.schedule({ if (queued == 0) core.release() }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    /** Free the model now (e.g. when it's deleted or the selection changes). */
    fun releaseAsync() { worker.execute { core.release() } }

    /** Delete every prepared-model file (Diagnostics → Clear Prepared Models). Runs when idle. */
    fun clearPrepared(ctx: Context, then: () -> Unit) {
        worker.execute {
            core.release()
            Models.preparedDir(ctx).listFiles()?.forEach { if (it.name.endsWith(".xnnpack_cache")) it.delete() }
            // Older versions put them in the app cache folder.
            ctx.cacheDir.listFiles()?.forEach { if (it.name.endsWith(".xnnpack_cache")) it.delete() }
            Diag.log("prepared models cleared")
            then()
        }
    }

    const val RESTARTING = "AI Engine got stuck and is restarting. Try again in a few seconds."
}
