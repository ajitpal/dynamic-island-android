package com.ajit.dynamicisland.module.speech

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.ajit.dynamicisland.sdk.speech.SpeechContract
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Transcribes audio files one at a time: decode → Silero VAD utterances → Omnilingual ASR (restricted
 * to Devanagari + Latin). The model stays loaded for a few minutes after the last job.
 */
object Transcriber {
    private const val TAG = "SpeechPack"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L

    interface Listener {
        fun line(startMs: Long, endMs: Long, text: String)
        fun progress(stage: String, fraction: Float)
        fun done()
        fun error(code: Int, message: String)
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "speech").apply { priority = Thread.NORM_PRIORITY } }
    private var recognizer: Recognizer? = null
    private var loaded: Model? = null
    private var unload: ScheduledFuture<*>? = null
    @Volatile var runningId: String? = null; private set
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Last finished job, for the Speech Pack screen. */
    @Volatile var lastStats: String? = null; private set

    fun busy() = runningId != null

    fun submit(ctx: Context, id: String, audio: ParcelFileDescriptor, l: Listener) {
        val app = ctx.applicationContext
        if (busy()) { runCatching { audio.close() }; l.error(SpeechContract.E_BUSY, "Speech Pack is transcribing another recording. Try again when it's finished."); return }
        runningId = id
        worker.execute {
            try { run(app, id, audio, l) }
            catch (t: Throwable) { Log.e(TAG, "transcription failed", t); l.error(SpeechContract.E_FAILED, t.message ?: t.javaClass.simpleName) }
            finally { runCatching { audio.close() }; runningId = null; cancelled.remove(id); scheduleUnload() }
        }
    }

    fun cancel(id: String) { cancelled += id }

    private fun run(ctx: Context, id: String, audio: ParcelFileDescriptor, l: Listener) {
        val model = Models.active(ctx) ?: run { l.error(SpeechContract.E_NO_MODEL, "No speech model yet. Open Speech Pack to download one."); return }
        unload?.cancel(false)
        val t0 = SystemClock.elapsedRealtime()
        if (loaded != model) {
            l.progress(SpeechContract.STAGE_LOADING, 0f)
            recognizer?.close(); recognizer = null; loaded = null
            recognizer = Recognizer(Models.file(ctx, model), Models.asset(ctx, "tokens.txt"), threads())
            loaded = model
        }
        val rec = recognizer!!
        val decoder = try { AudioDecoder(audio.fileDescriptor) } catch (e: Exception) { l.error(SpeechContract.E_BAD_AUDIO, "This audio file can't be read (${e.message})."); return }
        var lines = 0; var audioSec = 0.0
        decoder.use { dec ->
            Vad(Models.asset(ctx, "silero_vad.onnx")).use { vad ->
                val seg = SpeechSegmenter(vad::prob) { start, samples ->
                    if (id in cancelled) return@SpeechSegmenter
                    val text = rec.recognize(samples)
                    if (text.isNotBlank()) { lines++; l.line(start * 1000 / 16_000, (start + samples.size) * 1000 / 16_000, text) }
                }
                l.progress(SpeechContract.STAGE_TRANSCRIBING, 0f)
                var lastPush = 0L
                dec.decode { chunk, posUs ->
                    seg.accept(chunk); audioSec += chunk.size / 16_000.0
                    val now = SystemClock.elapsedRealtime()
                    if (dec.durationUs > 0 && now - lastPush > 500) { lastPush = now; l.progress(SpeechContract.STAGE_TRANSCRIBING, (posUs.toFloat() / dec.durationUs).coerceIn(0f, 0.99f)) }
                    id !in cancelled
                }
                if (id !in cancelled) seg.flush()
            }
        }
        if (id in cancelled) { l.error(SpeechContract.E_CANCELLED, "Cancelled"); return }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        lastStats = "${model.label} · ${"%.0f".format(audioSec)} s of audio in ${"%.0f".format(secs)} s · $lines lines"
        Log.i(TAG, lastStats!!)
        l.progress(SpeechContract.STAGE_TRANSCRIBING, 1f)
        l.done()
    }

    /** Big cores only, roughly: half the cores, between 2 and 4. */
    private fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun scheduleUnload() {
        unload?.cancel(false)
        unload = worker.schedule({ if (runningId == null) { recognizer?.close(); recognizer = null; loaded = null } }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    fun releaseAsync() { worker.execute { recognizer?.close(); recognizer = null; loaded = null } }
}
