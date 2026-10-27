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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.Transcriber
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ai.AiClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the microphone, transcribes it live on the device and saves audio + transcript.
 * Must be started from a visible activity (Android only lets a microphone service start "while in use").
 */
class RecorderService : Service() {

    enum class State { IDLE, STARTING, RECORDING, PAUSED, SAVING, SUMMARIZING }

    companion object {
        private const val TAG = "VoiceRecorder"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_CANCEL_NOTES = "cancelNotes"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEMPLATE = "template"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 7
        const val CARD_REC = "rec"
        const val CARD_SAVED = "saved"
        private const val RATE = 16_000
        private const val RED = 0xFFFF453A.toInt()
        private const val PURPLE = 0xFF5E5CE6.toInt()

        @Volatile var instance: RecorderService? = null
            private set
        /** UI listeners (main thread). */
        val listeners = mutableSetOf<() -> Unit>()
        val state: State get() = instance?.st ?: State.IDLE

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        /** Write AI notes for a saved recording. Call from a visible screen. */
        fun summarize(ctx: Context, id: String, t: Template) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_SUMMARIZE).putExtra(EXTRA_ID, id).putExtra(EXTRA_TEMPLATE, t.name))

        // Progress of the notes being written (read by the UI).
        @Volatile var notesFor: String? = null; private set
        @Volatile var notesStatus: String = ""; private set
        @Volatile var notesFraction: Float = 0f; private set
        @Volatile var notesPreview: String = ""; private set
        /** Last failure per recording id (cleared when a new attempt starts). */
        val notesErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun command(ctx: Context, action: String) {
            instance?.handle(action) ?: Unit
        }
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
    private lateinit var model: ModelStore.Size

    val elapsedMs: Long get() = recordedSamples * 1000 / RATE
    val liveCaption: String? get() = caption
    val lineCount: Int get() = synchronized(lines) { lines.size }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_SUMMARIZE) {
            val rid = intent?.getStringExtra(EXTRA_ID)
            val t = runCatching { Template.valueOf(intent?.getStringExtra(EXTRA_TEMPLATE)!!) }.getOrDefault(VoicePrefs.template(this))
            if (st == State.IDLE && rid != null) beginNotes(rid, t)
            else if (st == State.IDLE) stopSelf()
        } else handle(action)
        return START_NOT_STICKY
    }

    fun handle(action: String) {
        when (action) {
            ACTION_START -> if (st == State.IDLE) begin()
            ACTION_PAUSE -> if (st == State.RECORDING) { paused = true; st = State.PAUSED; changed(force = true) }
            ACTION_RESUME -> if (st == State.PAUSED) { paused = false; st = State.RECORDING; changed(force = true) }
            ACTION_CANCEL_NOTES -> if (st == State.SUMMARIZING) { notesCancelled = true; notesStatus = "Cancelling…"; changed(force = true) }
            ACTION_STOP -> if (st == State.RECORDING || st == State.PAUSED || st == State.STARTING) { stopRequested = true; st = State.SAVING; changed(force = true) }
        }
    }

    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        model = ModelStore.selected(this)
        if (!ModelStore.isPresent(this, model)) { stopSelf(); return }
        createChannel()
        try {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Started from the background (not allowed for the mic): the island tool must go through RecordActivity.
            Log.w(TAG, "startForeground refused", e); stopSelf(); return
        }
        startedAt = System.currentTimeMillis()
        id = Recordings.newId(startedAt)
        st = State.STARTING; stopRequested = false; paused = false; recordedSamples = 0; caption = null
        synchronized(lines) { lines.clear() }
        changed(force = true)
        thread = Thread({ run() }, "recorder").also { it.start() }
    }

    private fun run() {
        var transcriber: Transcriber? = null
        var rec: AudioRecord? = null
        var aac: AacWriter? = null
        val dir = Recordings.dir(this, id).apply { mkdirs() }
        try {
            transcriber = Transcriber().apply {
                loadFromFiles(ModelStore.dir(this@RecorderService, model).absolutePath, model.arch)
                addListener { ev -> ev.accept(visitor) }
                start()
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
                val chunk = if (n == f.size) f else FloatArray(n)
                for (i in 0 until n) chunk[i] = pcm[i] / 32768f
                transcriber.addAudio(chunk, RATE)
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
            val saved = synchronized(lines) { lines.toList() }
            if (recordedSamples > 0) {
                Recordings.save(this, Recording(id, defaultTitle(), startedAt, elapsedMs, saved, model.name))
            } else dir.deleteRecursively()
            main.post { finished(saved, recordedSamples > 0) }
        }
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

    // ---- island + notification ----

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
            State.STARTING -> b.title("Starting recording…").text("Loading the speech model").peek(3000)
            State.RECORDING -> {
                b.title("Recording").countUpFrom(System.currentTimeMillis() - elapsedMs)
                    .text(caption ?: "Listening… Let everyone know you're recording.")
                    .action(ACTION_PAUSE, "Pause").action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            }
            State.PAUSED -> b.title("Paused · ${Notes.clock(elapsedMs)}").text("Nothing is being recorded")
                .action(ACTION_RESUME, "Resume", ActionStyle.POSITIVE).action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            State.SAVING -> b.title("Saving…").text("Finishing the transcript")
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
                .title("Couldn't record").text(msg).icon(R.drawable.ic_mic).accent(RED).peek().expiresIn(15_000).build()
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

    // ---- AI notes (through the AI Engine module) ----

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
                if (notes.title != null && rec.title.startsWith("Meeting · ")) Recordings.rename(this, rid, notes.title)
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        if (instance === this) instance = null
        super.onDestroy()
    }
}
