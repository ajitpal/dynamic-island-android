package com.ajit.dynamicisland.module.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.Transcriber
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ai.AiClient
import com.ajit.dynamicisland.sdk.speech.SpeechClient
import com.ajit.dynamicisland.sdk.speech.SpeechContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the microphone, transcribes it on the device and saves audio + transcript. Also imports
 * audio files. Must be started from a visible activity (Android only lets a microphone service start
 * "while in use").
 *
 * Engines ([Plan]): English → Moonshine, live. Hindi/Hinglish → Android's on-device recognizer, live,
 * when the phone has offline Hindi; otherwise the Speech Pack module right after the recording.
 */
class RecorderService : Service() {

    enum class State { IDLE, STARTING, RECORDING, PAUSED, SAVING, TRANSCRIBING, SUMMARIZING }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_CANCEL_NOTES = "cancelNotes"
        const val ACTION_IMPORT = "import"
        const val ACTION_CANCEL_TRANSCRIBE = "cancelTranscribe"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEMPLATE = "template"
        private const val EXTRA_URI = "uri"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 7
        const val CARD_REC = "rec"
        const val CARD_SAVED = "saved"
        private const val RATE = 16_000
        private const val RED = 0xFFFF453A.toInt()
        private const val PURPLE = 0xFF5E5CE6.toInt()
        private const val ORANGE = 0xFFFF9500.toInt()

        @Volatile var instance: RecorderService? = null
            private set
        /** UI listeners (main thread). */
        val listeners = mutableSetOf<() -> Unit>()
        val state: State get() = instance?.st ?: State.IDLE

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        /** Write AI notes for a saved recording. Call from a visible screen. */
        fun summarize(ctx: Context, id: String, t: Template) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_SUMMARIZE).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEMPLATE, t.name))
        /** Import and transcribe an audio file (from the system file picker). Call from a visible screen. */
        fun import(ctx: Context, uri: Uri) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_IMPORT).putExtra(EXTRA_URI, uri.toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

        // Progress of the notes being written (read by the UI).
        @Volatile var notesFor: String? = null; private set
        @Volatile var notesStatus: String = ""; private set
        @Volatile var notesFraction: Float = 0f; private set
        @Volatile var notesPreview: String = ""; private set
        /** Last failure per recording id (cleared when a new attempt starts). */
        val notesErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        // Progress of a transcription after recording or of an import (read by the UI).
        @Volatile var transcribing: String? = null; private set
        @Volatile var transcribeStatus: String = ""; private set
        @Volatile var transcribeFraction: Float = 0f; private set
        /** The last thing that couldn't start or finish, for the Meeting Notes screen. */
        @Volatile var lastProblem: String? = null; private set

        fun command(ctx: Context, action: String) { instance?.handle(action) ?: Unit }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var st = State.IDLE
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    private var startedAt = 0L
    @Volatile private var recordedSamples = 0L
    private val lines = mutableListOf<Line>()
    @Volatile private var caption: String? = null
    private var lastCardAt = 0L
    private lateinit var id: String
    private var lang = Lang.EN
    private var plan: Plan = Plan.MoonshineLive
    private var engineName = ""
    private var stt: AndroidStt? = null
    private var speech: SpeechClient? = null
    private var speechJob: String? = null

    val elapsedMs: Long get() = recordedSamples * 1000 / RATE
    val liveCaption: String? get() = caption
    val lineCount: Int get() = synchronized(lines) { lines.size }
    val livePlan: Plan get() = plan

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action ?: ACTION_START) {
            ACTION_SUMMARIZE -> {
                val rid = intent?.getStringExtra(EXTRA_ID)
                val t = runCatching { Template.valueOf(intent?.getStringExtra(EXTRA_TEMPLATE)!!) }.getOrDefault(VoicePrefs.template(this))
                if (st == State.IDLE && rid != null) beginNotes(rid, t) else if (st == State.IDLE) stopSelf()
            }
            ACTION_IMPORT -> {
                val uri = intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (st == State.IDLE && uri != null) beginImport(uri) else if (st == State.IDLE) stopSelf()
            }
            else -> handle(action)
        }
        return START_NOT_STICKY
    }

    fun handle(action: String) {
        when (action) {
            ACTION_START -> if (st == State.IDLE) begin()
            ACTION_PAUSE -> if (st == State.RECORDING) { paused = true; st = State.PAUSED; changed(force = true) }
            ACTION_RESUME -> if (st == State.PAUSED) { paused = false; st = State.RECORDING; changed(force = true) }
            ACTION_CANCEL_NOTES -> if (st == State.SUMMARIZING) { notesCancelled = true; notesStatus = "Cancelling…"; changed(force = true) }
            ACTION_CANCEL_TRANSCRIBE -> if (st == State.TRANSCRIBING) { transcribeCancelled = true; speechJob?.let { speech?.cancel(it) }; transcribeStatus = "Cancelling…"; changed(force = true) }
            ACTION_STOP -> if (st == State.RECORDING || st == State.PAUSED || st == State.STARTING) { stopRequested = true; st = State.SAVING; changed(force = true) }
        }
    }

    // ------------------------------------------------------------------ choosing the engine

    private fun choosePlan(then: (Plan) -> Unit) {
        lang = VoicePrefs.language(this)
        if (!lang.isHindi) { then(Plan.MoonshineLive); return }
        AndroidStt.check(this) { android ->
            VoicePrefs.setAndroidHindi(this, android)
            val finish = { ready: Boolean ->
                then(Plan.choose(lang, android, ready, SpeechClient.isTrusted(this), VoicePrefs.preferSpeechPack(this)))
            }
            if (!SpeechClient.isTrusted(this)) finish(false)
            else SpeechClient(this).let { c -> c.status { b -> c.close(); finish(b?.getBoolean(SpeechContract.S_READY) == true) } }
        }
    }

    private fun planProblem(p: Plan.NeedsSetup): String = when {
        p.androidCanDownload -> "To transcribe Hindi, download Hindi for Android's offline speech recognition (Meeting Notes → Language), or install Speech Pack."
        p.speechPackInstalled -> "Open Speech Pack and download its model to transcribe Hindi."
        else -> "This phone's Android has no offline Hindi. Install the Speech Pack module to transcribe Hindi and Hinglish."
    }

    // ------------------------------------------------------------------ recording

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        createChannel()
        try {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Started from the background (not allowed for the mic): the island tool must go through RecordActivity.
            Log.w(TAG, "startForeground refused", e); stopSelf(); return
        }
        st = State.STARTING; stopRequested = false; paused = false; recordedSamples = 0; caption = null; lastProblem = null
        synchronized(lines) { lines.clear() }
        changed(force = true)
        choosePlan { p ->
            plan = p
            when (p) {
                is Plan.NeedsSetup -> { fail(planProblem(p)); return@choosePlan }
                Plan.MoonshineLive -> if (!ModelStore.isPresent(this)) { fail("Download the English speech model in Meeting Notes first."); return@choosePlan }
                else -> {}
            }
            if (stopRequested) { quit(); return@choosePlan }
            startedAt = System.currentTimeMillis()
            id = Recordings.newId(startedAt)
            engineName = when (p) {
                Plan.MoonshineLive -> ModelStore.selected(this).name
                Plan.AndroidLive -> "android-hi"
                else -> "speechpack"
            }
            if (p == Plan.AndroidLive) stt = AndroidStt(this, lang,
                onPartial = { caption = it; changed() },
                onLine = { start, text -> synchronized(lines) { lines += Line(start, text) }; caption = text; changed() },
                onError = { msg -> main.post { postError(msg) } },
            ).also { it.start() }
            thread = Thread({ run() }, "recorder").also { it.start() }
        }
    }

    private fun fail(msg: String) {
        lastProblem = msg
        postError(msg)
        quit()
    }

    private fun quit() {
        st = State.IDLE
        IslandModuleService.current?.island?.remove(CARD_REC)
        listeners.toList().forEach { it() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun run() {
        var transcriber: Transcriber? = null
        var rec: AudioRecord? = null
        var aac: AacWriter? = null
        val dir = Recordings.dir(this, id).apply { mkdirs() }
        try {
            if (plan == Plan.MoonshineLive) {
                val model = ModelStore.selected(this)
                transcriber = Transcriber().apply {
                    loadFromFiles(ModelStore.dir(this@RecorderService, model).absolutePath, model.arch)
                    addListener { ev -> ev.accept(visitor) }
                    start()
                }
            }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            aac = AacWriter(File(dir, "audio.m4a"), RATE)
            rec.startRecording()
            if (!stopRequested) { st = State.RECORDING; main.post { changed(force = true) } }
            val pcm = ShortArray(RATE / 10) // 100 ms
            val f = FloatArray(pcm.size)
            while (!stopRequested) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) { if (n < 0) SystemClock.sleep(20); continue }
                if (paused) continue
                if (transcriber != null) {
                    val chunk = if (n == f.size) f else FloatArray(n)
                    for (i in 0 until n) chunk[i] = pcm[i] / 32768f
                    transcriber.addAudio(chunk, RATE)
                }
                stt?.write(pcm, n)
                aac.write(pcm, n)
                recordedSamples += n
                main.post { changed() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording failed", t)
            main.post { postError(t.message ?: "Recording failed") }
        } finally {
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            runCatching { transcriber?.stop() }   // flushes the last line
            runCatching { transcriber?.close() }
            val audioOk = aac?.finish() == true
            if (!audioOk) File(dir, "audio.m4a").delete()
            // Android's recognizer delivers its last words after the audio closes.
            main.post { stt?.let { s -> s.stop { stt = null; saveRecording(audioOk) } } ?: saveRecording(audioOk) }
        }
    }

    private fun saveRecording(audioOk: Boolean) {
        val dir = Recordings.dir(this, id)
        val saved = synchronized(lines) { lines.sortedBy { it.startMs } }
        if (recordedSamples == 0L) { dir.deleteRecursively(); finished(emptyList(), false); return }
        Recordings.save(this, Recording(id, defaultTitle(), startedAt, elapsedMs, saved, engineName, lang.name.lowercase()))
        if (plan == Plan.SpeechPackAfter && audioOk) { transcribeWithSpeechPack(id, File(dir, "audio.m4a")); return }
        finished(saved, true)
    }

    private val visitor = object : TranscriptEvent.Visitor {
        override fun onLineStarted(e: TranscriptEvent.LineStarted) {}
        override fun onLineUpdated(e: TranscriptEvent.LineUpdated) {}
        override fun onLineSpeakersChanged(e: TranscriptEvent.LineSpeakersChanged) {}
        override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
            caption = e.line.text?.trim()?.takeIf { it.isNotEmpty() }
            main.post { changed() }
        }
        override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
            val text = e.line.text?.trim().orEmpty()
            if (text.isNotEmpty()) synchronized(lines) { lines += Line((e.line.startTime * 1000).toLong(), text) }
            caption = text.ifEmpty { caption }
            main.post { changed() }
        }
        override fun onError(e: TranscriptEvent.Error) { Log.w(TAG, "transcriber error", e.cause) }
    }

    private fun defaultTitle() = "Meeting · " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(startedAt))

    // ------------------------------------------------------------------ transcribing after recording, and imports

    @Volatile private var transcribeCancelled = false

    /** Hindi without live captions: the Speech Pack writes the transcript from the saved audio. */
    private fun transcribeWithSpeechPack(rid: String, audio: File, imported: Boolean = false) {
        st = State.TRANSCRIBING; transcribing = rid; transcribeStatus = "Starting Speech Pack…"; transcribeFraction = 0f; transcribeCancelled = false
        try { startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (e: Exception) { Log.w(TAG, "dataSync refused", e) }
        changed(force = true)
        val got = mutableListOf<Line>()
        val c = SpeechClient(this).also { speech = it }
        speechJob = c.transcribe(audio, "hi",
            onLine = { start, _, text -> got += Line(start, text); caption = text; changed() },
            onProgress = { stage, f ->
                transcribeStatus = if (stage == SpeechContract.STAGE_LOADING) "Loading the Hindi speech model" else "Writing the transcript · ${(f * 100).toInt()}%"
                transcribeFraction = f; changed()
            },
            onDone = { c.close(); speech = null; transcribed(rid, got, null) },
            onError = { code, msg -> c.close(); speech = null; transcribed(rid, got, if (code == SpeechContract.E_CANCELLED) null else msg) },
        )
    }

    private fun transcribed(rid: String, got: List<Line>, err: String?) {
        transcribing = null; speechJob = null
        val r = Recordings.load(this, rid)
        if (r != null) Recordings.save(this, r.copy(lines = got.sortedBy { it.startMs }))
        if (err != null) { lastProblem = "Transcript incomplete: $err"; postError("Transcript incomplete: $err") }
        val saved = got.sortedBy { it.startMs }
        if (r != null) { startedAt = r.startedAt; recordedSamples = r.durationMs * RATE / 1000 }
        id = rid
        finished(saved, r != null && !transcribeCancelled)
    }

    /** Copy an audio file in and transcribe it with the engine for the chosen language. */
    private fun beginImport(uri: Uri) {
        createChannel()
        try { startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (e: Exception) { Log.w(TAG, "dataSync refused", e); stopSelf(); return }
        st = State.TRANSCRIBING; transcribeStatus = "Importing…"; transcribeFraction = 0f; transcribeCancelled = false; lastProblem = null; caption = null
        synchronized(lines) { lines.clear() }
        changed(force = true)
        choosePlan { p ->
            plan = p
            if (p is Plan.NeedsSetup) { transcribing = null; fail(planProblem(p)); return@choosePlan }
            if (p == Plan.MoonshineLive && !ModelStore.isPresent(this)) { transcribing = null; fail("Download the English speech model in Meeting Notes first."); return@choosePlan }
            Thread({ importWork(uri, p) }, "import").start()
        }
    }

    private fun importWork(uri: Uri, p: Plan) {
        startedAt = System.currentTimeMillis()
        id = Recordings.newId(startedAt)
        transcribing = id
        val dir = Recordings.dir(this, id).apply { mkdirs() }
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: "audio"
        val ext = name.substringAfterLast('.', "m4a").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "m4a" }
        val file = File(dir, "audio.$ext")
        try {
            contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
            val durMs = runCatching { android.media.MediaMetadataRetriever().use { m -> m.setDataSource(file.path); m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() } }.getOrDefault(0L)
            val title = name.substringBeforeLast('.').take(60).ifBlank { "Imported audio" }
            engineName = when (p) { Plan.MoonshineLive -> ModelStore.selected(this).name; Plan.AndroidLive -> "android-hi"; else -> "speechpack" }
            Recordings.save(this, Recording(id, title, startedAt, durMs, emptyList(), engineName, lang.name.lowercase(), imported = true))
            recordedSamples = durMs * RATE / 1000
            when (p) {
                Plan.SpeechPackAfter -> main.post { transcribeWithSpeechPack(id, file, imported = true) }
                Plan.MoonshineLive -> decodeInto(file, durMs) { chunk, tr -> tr!!.addAudio(chunk, RATE) }
                Plan.AndroidLive -> decodeInto(file, durMs) { chunk, _ ->
                    val s = ShortArray(chunk.size) { (chunk[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                    stt?.write(s, s.size)
                }
                else -> {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "import failed", t)
            main.post { transcribing = null; dir.deleteRecursively(); fail("Couldn't import this file: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    /** Decode an imported file and feed it to Moonshine or Android's recognizer (whichever [plan] says). */
    private fun decodeInto(file: File, durMs: Long, feed: (FloatArray, Transcriber?) -> Unit) {
        var transcriber: Transcriber? = null
        if (plan == Plan.MoonshineLive) {
            val model = ModelStore.selected(this)
            transcriber = Transcriber().apply { loadFromFiles(ModelStore.dir(this@RecorderService, model).absolutePath, model.arch); addListener { ev -> ev.accept(visitor) }; start() }
        } else {
            val ready = java.util.concurrent.CountDownLatch(1)
            main.post {
                stt = AndroidStt(this, lang, onPartial = { caption = it; changed() },
                    onLine = { start, text -> synchronized(lines) { lines += Line(start, text) }; caption = text; changed() },
                    onError = { msg -> postError(msg) }).also { it.start() }
                ready.countDown()
            }
            ready.await()
        }
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            AudioDecoder(pfd.fileDescriptor).use { dec ->
                dec.decode { chunk, posUs ->
                    feed(chunk, transcriber)
                    if (durMs > 0) { transcribeFraction = (posUs / 1000f / durMs).coerceIn(0f, 0.99f); transcribeStatus = "Writing the transcript · ${(transcribeFraction * 100).toInt()}%"; main.post { changed() } }
                    !transcribeCancelled
                }
            }
        }
        runCatching { transcriber?.stop() }; runCatching { transcriber?.close() }
        main.post {
            val done = { val got = synchronized(lines) { lines.toList() }; stt = null; transcribed(id, got, null) }
            stt?.stop { done() } ?: done()
        }
    }

    // ------------------------------------------------------------------ island + notification

    /** Throttled to one island update per second unless [force]. */
    private fun changed(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCardAt < 1000) return
        lastCardAt = now
        postCard()
        if (st != State.IDLE && st != State.SAVING) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        listeners.toList().forEach { it() }
    }

    private fun postCard() {
        val island = IslandModuleService.current?.island ?: return
        val b = IslandCard.Builder(this, CARD_REC).icon(R.drawable.ic_mic).accent(RED)
        when (st) {
            State.STARTING -> b.title("Starting recording…").text(if (lang.isHindi) "Getting Hindi speech recognition ready" else "Loading the speech model").peek(3000)
            State.RECORDING -> {
                b.title("Recording").countUpFrom(System.currentTimeMillis() - elapsedMs)
                    .text(caption ?: if (plan == Plan.SpeechPackAfter) "Hindi transcript is written after you stop." else "Listening… Let everyone know you're recording.")
                    .action(ACTION_PAUSE, "Pause").action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            }
            State.PAUSED -> b.title("Paused · ${Notes.clock(elapsedMs)}").text("Nothing is being recorded")
                .action(ACTION_RESUME, "Resume", ActionStyle.POSITIVE).action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            State.SAVING -> b.title("Saving…").text("Finishing the transcript")
            State.TRANSCRIBING -> {
                b.title("Transcribing · ${(transcribeFraction * 100).toInt()}%").text(caption ?: transcribeStatus).icon(R.drawable.ic_notes).accent(ORANGE)
                    .progress(transcribeFraction.coerceIn(0.02f, 1f)).style(com.ajit.dynamicisland.sdk.IslandContract.STYLE_PROGRESS)
                    .action(ACTION_CANCEL_TRANSCRIBE, "Cancel", ActionStyle.NEGATIVE)
            }
            State.SUMMARIZING -> {
                b.title("Writing notes · ${(notesFraction * 100).toInt()}%").text(notesStatus).icon(R.drawable.ic_spark).accent(PURPLE)
                    .progress(notesFraction.coerceIn(0.02f, 1f)).style(com.ajit.dynamicisland.sdk.IslandContract.STYLE_PROGRESS)
                    .action(ACTION_CANCEL_NOTES, "Cancel", ActionStyle.NEGATIVE)
                notesFor?.let { b.tapActivity(TranscriptActivity::class.java, it) }
            }
            State.IDLE -> { island.remove(CARD_REC); return }
        }
        island.post(b.build())
    }

    private fun postError(msg: String) {
        IslandModuleService.current?.island?.post(
            IslandCard.Builder(this, CARD_SAVED).style(com.ajit.dynamicisland.sdk.IslandContract.STYLE_ALERT)
                .title("Couldn't transcribe").text(msg).icon(R.drawable.ic_mic).accent(RED).peek().expiresIn(15_000)
                .tapActivity(MainActivity::class.java).build()
        )
    }

    private fun finished(saved: List<Line>, hasAudio: Boolean) {
        st = State.IDLE
        val island = IslandModuleService.current?.island
        island?.remove(CARD_REC)
        if (hasAudio && saved.isNotEmpty() && VoicePrefs.autoNotes(this) && AiClient.isTrusted(this)) {
            listeners.toList().forEach { it() }
            beginNotes(id, VoicePrefs.template(this)); return
        }
        if (hasAudio && island != null) {
            val items = Notes.actionItems(saved).size
            val words = Notes.wordCount(saved)
            island.post(
                IslandCard.Builder(this, CARD_SAVED).title("Meeting saved")
                    .text("${Notes.clock(elapsedMs)} · $words words" + if (items > 0) " · $items possible action items" else "")
                    .icon(R.drawable.ic_notes).accent(0xFF30D158.toInt())
                    .tapActivity(TranscriptActivity::class.java, id)
                    .activityAction("open", "Open notes", TranscriptActivity::class.java, id, ActionStyle.POSITIVE)
                    .peek(6000).expiresIn(120_000).build()
            )
        }
        listeners.toList().forEach { it() }
        VoiceModule.refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a meeting is being recorded"
            })
    }

    private fun notification(): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecorderService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = st == State.PAUSED
        if (st == State.SUMMARIZING) return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_spark).setContentTitle("Writing meeting notes").setContentText(notesStatus)
            .setProgress(100, (notesFraction * 100).toInt(), notesFraction <= 0f)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", pi(ACTION_CANCEL_NOTES, 3)).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build()
        if (st == State.TRANSCRIBING) return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notes).setContentTitle("Transcribing").setContentText(transcribeStatus)
            .setProgress(100, (transcribeFraction * 100).toInt(), transcribeFraction <= 0f)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", pi(ACTION_CANCEL_TRANSCRIBE, 4)).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (paused) "Recording paused" else "Recording meeting")
            .setContentText(caption ?: "Transcribing on this phone")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setUsesChronometer(!paused).setWhen(System.currentTimeMillis() - elapsedMs)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", pi(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Stop", pi(ACTION_STOP, 2)).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ------------------------------------------------------------------ AI notes (through the AI Engine module)

    @Volatile private var notesCancelled = false

    private fun beginNotes(rid: String, t: Template) {
        val rec = Recordings.load(this, rid) ?: run { if (st == State.IDLE) stopSelf(); return }
        createChannel()
        st = State.SUMMARIZING; notesFor = rid; notesStatus = "Starting the AI Engine…"; notesFraction = 0f; notesPreview = ""; notesCancelled = false
        notesErrors.remove(rid)
        try {
            // Not using the mic any more: switch the service type to a data job.
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) { Log.w(TAG, "startForeground(dataSync) refused", e); st = State.IDLE; notesFor = null; stopSelf(); return }
        changed(force = true)
        Thread({
            var err: String? = null
            var notes: AiNotes? = null
            try {
                notes = NoteWriter(this).write(rec, t, { status, frac, preview ->
                    notesStatus = status; notesFraction = frac; notesPreview = preview
                    main.post { changed() }
                }, { notesCancelled })
                NotesStore.save(this, rid, notes)
                // Use the AI title unless the user already renamed the meeting.
                if (notes.title != null && (rec.title.startsWith("Meeting · ") || rec.imported)) Recordings.rename(this, rid, notes.title)
            } catch (e: NoteWriter.Failed) { err = if (notesCancelled) null else e.message }
            catch (t: Throwable) { Log.e(TAG, "notes failed", t); err = t.message ?: "Couldn't write notes" }
            main.post { notesDone(rid, notes, err) }
        }, "notes").start()
    }

    private fun notesDone(rid: String, notes: AiNotes?, err: String?) {
        st = State.IDLE; notesFor = null
        if (err != null) notesErrors[rid] = err
        val island = IslandModuleService.current?.island
        island?.remove(CARD_REC)
        val title = Recordings.load(this, rid)?.title ?: "Meeting"
        if (island != null && !notesCancelled) {
            val b = IslandCard.Builder(this, CARD_SAVED).tapActivity(TranscriptActivity::class.java, rid).expiresIn(120_000)
            if (notes != null) {
                val actions = notes.sections.firstOrNull { it.first == "Action items" || it.first == "Follow-ups" || it.first == "Next" }?.second?.size ?: 0
                b.title("Notes ready · $title").text(notes.sections.firstOrNull { it.second.isNotEmpty() }?.second?.first() ?: "Tap to open")
                    .compact(if (actions > 0) "$actions to-dos" else "Notes")
                    .icon(R.drawable.ic_spark).accent(0xFF30D158.toInt()).peek(6000)
                    .activityAction("open", "Open notes", TranscriptActivity::class.java, rid, ActionStyle.POSITIVE)
            } else {
                b.title("Meeting saved, notes failed").text(err ?: "Open it to try again").icon(R.drawable.ic_notes).accent(0xFFFF9F0A.toInt()).peek(5000)
                    .activityAction("open", "Open", TranscriptActivity::class.java, rid)
            }
            island.post(b.build())
        }
        listeners.toList().forEach { it() }
        VoiceModule.refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        if (instance === this) instance = null
        super.onDestroy()
    }
}
