package com.ajit.dynamicisland.module.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Android's own on-device speech recognizer, fed with our microphone audio through a pipe (Android 13+),
 * so recording, saving and the island work exactly as for English. One segmented session runs until
 * the pipe closes; if Android ends it early, a new one starts and the audio keeps flowing.
 *
 * SpeechRecognizer must be used on the main thread; [write] may be called from the recording thread.
 */
class AndroidStt(
    private val ctx: Context, private val lang: Lang,
    private val onPartial: (String) -> Unit, private val onLine: (Long, String) -> Unit, private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AndroidStt"
        const val RATE = 16_000
        private val HINDI = "hi-IN"

        fun intent(lang: Lang): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, HINDI)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (lang == Lang.HINGLISH && Build.VERSION.SDK_INT >= 34) {
                // Let the recognizer switch between Hindi and Indian English within the meeting.
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf(HINDI, "en-IN"))
            }
        }

        /** Does this phone's on-device recognizer have Hindi? Main thread; [then] on the main thread. */
        fun check(ctx: Context, then: (AndroidHindi) -> Unit) {
            if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) { then(AndroidHindi.UNSUPPORTED); return }
            val sr = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (e: Exception) { then(AndroidHindi.UNSUPPORTED); return }
            val main = Handler(Looper.getMainLooper())
            var answered = false
            fun answer(a: AndroidHindi) { if (!answered) { answered = true; main.post { runCatching { sr.destroy() }; then(a) } } }
            main.postDelayed({ answer(AndroidHindi.UNKNOWN) }, 5000)
            try {
                sr.checkRecognitionSupport(intent(Lang.HI), Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        fun has(l: List<String>) = l.any { it.startsWith("hi", ignoreCase = true) }
                        answer(when {
                            has(s.installedOnDeviceLanguages) -> AndroidHindi.READY
                            has(s.supportedOnDeviceLanguages) || has(s.pendingOnDeviceLanguages) -> AndroidHindi.DOWNLOADABLE
                            else -> AndroidHindi.UNSUPPORTED
                        })
                    }
                    override fun onError(error: Int) { answer(AndroidHindi.UNSUPPORTED) }
                })
            } catch (e: Exception) { answer(AndroidHindi.UNSUPPORTED) }
        }

        /** Ask Android to download offline Hindi (it shows its own progress). Main thread. */
        fun download(ctx: Context, then: (String?) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) { then("Needs Android 13 or newer"); return }
            val sr = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (e: Exception) { then(e.message); return }
            val main = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 34) {
                sr.triggerModelDownload(intent(Lang.HI), Executors.newSingleThreadExecutor(), object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() { main.post { runCatching { sr.destroy() }; then(null) } }
                    override fun onScheduled() { main.post { runCatching { sr.destroy() }; then(null) } }
                    override fun onError(error: Int) { main.post { runCatching { sr.destroy() }; then("Android couldn't download Hindi (error $error)") } }
                })
            } else {
                sr.triggerModelDownload(intent(Lang.HI)); main.postDelayed({ runCatching { sr.destroy() }; then(null) }, 1000)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopping = false
    @Volatile private var written = 0L          // samples written so far
    private var segmentStart = 0L                // samples at the start of the current segment
    private var finished: (() -> Unit)? = null
    private var restarts = 0

    /** Main thread. */
    fun start() { stopping = false; startSession() }

    private fun startSession() {
        val pipe = ParcelFileDescriptor.createPipe()
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        segmentStart = written
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(listener)
        val i = intent(lang).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            // One session for as long as the audio source stays open.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        r.startListening(i)
        runCatching { pipe[0].close() }   // the recognizer has its own copy now
    }

    /** Recording thread: 16 kHz mono 16-bit samples. */
    fun write(pcm: ShortArray, n: Int) {
        val o = out ?: return
        val bytes = ByteArray(n * 2)
        for (k in 0 until n) { val s = pcm[k].toInt(); bytes[2 * k] = s.toByte(); bytes[2 * k + 1] = (s shr 8).toByte() }
        try { o.write(bytes); written += n } catch (e: IOException) { /* session ended; a new one is starting */ }
    }

    /** Main thread. Closes the audio, waits (up to 10 s) for the last words, then [then]. */
    fun stop(then: () -> Unit) {
        stopping = true
        finished = then
        runCatching { out?.close() }; out = null
        main.postDelayed({ finish() }, 10_000)
    }

    private fun finish() {
        val f = finished ?: return
        finished = null
        runCatching { sr?.destroy() }; sr = null
        f()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(onPartial)
        }
        override fun onSegmentResults(segment: Bundle) {
            val text = segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            val start = segmentStart * 1000 / RATE
            segmentStart = written
            if (text.isNotEmpty()) onLine(start, text)
        }
        override fun onEndOfSegmentedSession() { endedSession() }
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { onLine(segmentStart * 1000 / RATE, it) }
            endedSession()
        }
        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error")
            if (!stopping && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                onError("Hindi isn't available in Android's offline speech recognition on this phone"); stopping = true
            }
            endedSession()
        }
    }

    /** The session ended: finish if we're stopping, otherwise start a new one so the meeting keeps being transcribed. */
    private fun endedSession() {
        runCatching { sr?.destroy() }; sr = null
        if (stopping) { finish(); return }
        runCatching { out?.close() }; out = null
        if (++restarts > 200) { onError("Android's speech recognizer keeps stopping"); return }
        main.postDelayed({ if (!stopping) startSession() }, 300)
    }
}
