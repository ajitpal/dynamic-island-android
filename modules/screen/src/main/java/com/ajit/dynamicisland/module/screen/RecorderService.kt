package com.ajit.dynamicisland.module.screen

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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.WindowManager
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.IslandModuleService
import java.util.Calendar

/**
 * Runs one screen recording: Android's consent → optional 3-2-1 countdown in the island → recording
 * with a red REC timer and Pause / Stop (island card and notification) → saved to Movies/Dynamic Island
 * → a "Saved" card with Play and Share.
 */
class RecorderService : Service() {
    enum class State { IDLE, COUNTDOWN, RECORDING, PAUSED, SAVING }

    companion object {
        private const val TAG = "ScreenRecorder"
        const val CARD = "rec"
        const val CARD_SAVED = "saved"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 7
        private const val RED = 0xFFFF3B30.toInt()
        private const val GREEN = 0xFF30D158.toInt()

        @Volatile var instance: RecorderService? = null
            private set
        var state = State.IDLE
            private set
        /** Set when the microphone was refused just now: this one recording has no sound. */
        @Volatile var noSoundOnce = false
        val active get() = state != State.IDLE
        fun elapsedMs() = instance?.capture?.elapsedMs() ?: 0L

        private val listeners = mutableListOf<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }

        fun start(ctx: Context, code: Int, data: Intent) = ctx.startForegroundService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))

        fun command(ctx: Context, action: String) { instance?.handle(action) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var audio = Audio.NONE
    private var countdown = 0
    private var savedAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        // The person stopped sharing from Android's own controls (status bar chip, lock screen…).
        override fun onStop() { main.post { if (state == State.RECORDING || state == State.PAUSED) handle(ACTION_STOP) else if (state == State.COUNTDOWN) cancel() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this; createChannel() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (state == State.IDLE) begin(intent) else Unit
            null -> Unit
            else -> handle(intent.action!!)
        }
        return START_NOT_STICKY
    }

    fun handle(action: String) {
        when (action) {
            ACTION_PAUSE -> if (state == State.RECORDING) { capture?.pause(); state = State.PAUSED; changed() }
            ACTION_RESUME -> if (state == State.PAUSED) { capture?.resume(); state = State.RECORDING; changed() }
            ACTION_STOP -> when (state) {
                State.COUNTDOWN -> cancel()
                State.RECORDING, State.PAUSED -> save()
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ start

    private fun begin(intent: Intent) {
        audio = if (noSoundOnce) Audio.NONE else Prefs.audio(this)
        noSoundOnce = false
        if (audio.usesMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audio = Audio.NONE
        if (audio.usesPlayback && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audio = Audio.NONE
        // Android 14+: the foreground service must be running (type mediaProjection) before asking for the projection.
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (audio.usesMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        state = State.COUNTDOWN
        try { startForeground(NOTIF_ID, notification(), type) } catch (e: Exception) {
            Log.w(TAG, "startForeground refused", e); state = State.IDLE; stopSelf(); return
        }
        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val p = try { data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), it) } } catch (e: Exception) { Log.w(TAG, "projection", e); null }
        if (p == null) { error("Android didn't allow recording the screen."); finish(); return }
        p.registerCallback(projectionCallback, main)
        projection = p
        countdown = if (Prefs.countdown(this)) 3 else 0
        tick()
    }

    private fun tick() {
        if (state != State.COUNTDOWN) return
        if (countdown <= 0) { record(); return }
        changed()
        countdown--
        main.postDelayed({ tick() }, 1000)
    }

    private fun record() {
        val p = projection ?: return
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        val (w, h) = ScreenCapture.supportedSize(bounds.width(), bounds.height(), Prefs.quality(this), Prefs.fps(this))
        val c = Calendar.getInstance()
        val name = Plan.fileName(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
        try {
            val u = Recordings.create(this, name) ?: throw IllegalStateException("Couldn't create the video file")
            uri = u
            val fd = contentResolver.openFileDescriptor(u, "rw") ?: throw IllegalStateException("Couldn't open the video file")
            pfd = fd
            val cap = ScreenCapture(p, w, h, resources.displayMetrics.densityDpi, Prefs.fps(this), audio, fd.fileDescriptor) { msg ->
                main.post { if (state == State.RECORDING || state == State.PAUSED) { error(msg); save() } }
            }
            capture = cap
            cap.start()
            state = State.RECORDING
            changed()
            if (cap.audioUnavailable) error(if (audio.usesPlayback && !audio.usesMic) "Phone sound isn't available right now, so this recording has no sound." else "Some sound couldn't be recorded on this phone.", alert = false)
        } catch (e: Exception) {
            Log.w(TAG, "record", e)
            error("Couldn't start recording: ${e.message}")
            capture?.stop(); capture = null
            pfd?.close(); pfd = null
            uri?.let { Recordings.delete(this, it) }; uri = null
            finish()
        }
    }

    // ------------------------------------------------------------------ stop

    private fun cancel() { finish() }

    private fun save() {
        val cap = capture ?: return finish()
        state = State.SAVING
        changed()
        Thread {
            val elapsed = cap.elapsedMs()
            val ok = cap.stop()
            runCatching { pfd?.close() }
            val u = uri
            if (u != null) { if (ok) runCatching { Recordings.publish(this, u) } else Recordings.delete(this, u) }
            main.post {
                if (ok && u != null) savedCard(u, elapsed) else error("Nothing was recorded. Try again and record for a little longer.")
                finish()
            }
        }.start()
    }

    private fun finish() {
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null; capture = null; pfd = null; uri = null
        state = State.IDLE
        IslandModuleService.current?.island?.remove(CARD)
        listeners.toList().forEach { it() }
        ScreenModule.refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ island + notification

    private fun changed() {
        postCard()
        if (state != State.IDLE) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        listeners.toList().forEach { it() }
    }

    /** After the island reconnects (e.g. it was restarted), show the card again. */
    fun repost() { if (state != State.IDLE) postCard() }

    private fun postCard() {
        val island = IslandModuleService.current?.island ?: return
        val b = IslandCard.Builder(this, CARD).icon(R.drawable.ic_screen_rec).accent(RED)
        val elapsed = capture?.elapsedMs() ?: 0L
        when (state) {
            State.COUNTDOWN -> b.title("Recording in $countdown…").text("Screen recording starts in a moment").compact("$countdown")
                .action(ACTION_STOP, "Cancel", ActionStyle.NEGATIVE).peek(1500)
            State.RECORDING -> b.title("Recording Screen").countUpFrom(System.currentTimeMillis() - elapsed)
                .text(if (audio == Audio.NONE) "No sound" else audio.label)
                .action(ACTION_PAUSE, "Pause").action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            State.PAUSED -> b.title("Paused · ${Plan.clock(elapsed)}").text("Nothing is being recorded").compact("❚❚")
                .action(ACTION_RESUME, "Resume", ActionStyle.POSITIVE).action(ACTION_STOP, "Stop", ActionStyle.NEGATIVE)
            State.SAVING -> b.title("Saving…").text("Finishing the video").compact("…")
            State.IDLE -> { island.remove(CARD); return }
        }
        island.post(b.build())
    }

    private fun savedCard(u: Uri, elapsed: Long) {
        IslandModuleService.current?.island?.post(
            IslandCard.Builder(this, CARD_SAVED).title("Screen Recording Saved")
                .text("${Plan.clock(elapsed)} · Movies › Dynamic Island").compact(Plan.clock(elapsed))
                .icon(R.drawable.ic_check).accent(GREEN)
                .tapActivity(OpenActivity::class.java, OpenActivity.arg(OpenActivity.PLAY, u))
                .activityAction("play", "Play", OpenActivity::class.java, OpenActivity.arg(OpenActivity.PLAY, u), ActionStyle.POSITIVE)
                .activityAction("share", "Share", OpenActivity::class.java, OpenActivity.arg(OpenActivity.SHARE, u))
                .peek(5000).expiresIn(120_000).build()
        )
    }

    private fun error(msg: String, alert: Boolean = true) {
        IslandModuleService.current?.island?.post(
            IslandCard.Builder(this, CARD_SAVED).apply { if (alert) style(IslandContract.STYLE_ALERT) }
                .title("Screen Recorder").text(msg).icon(R.drawable.ic_info).accent(if (alert) RED else 0xFFFF9F0A.toInt())
                .peek(4000).expiresIn(15_000).tapActivity(MainActivity::class.java).build()
        )
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the screen is being recorded"
            })
    }

    private fun notification(): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = state == State.PAUSED
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_screen_rec)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return when (state) {
            State.RECORDING, State.PAUSED -> b.setContentTitle(if (paused) "Screen recording paused" else "Recording screen")
                .setContentText(if (audio == Audio.NONE) "No sound" else audio.label)
                .setUsesChronometer(!paused).setShowWhen(!paused).setWhen(System.currentTimeMillis() - (capture?.elapsedMs() ?: 0))
                .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", pi(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)).build())
                .addAction(Notification.Action.Builder(null, "Stop", pi(ACTION_STOP, 2)).build()).build()
            State.SAVING -> b.setContentTitle("Saving screen recording").setProgress(0, 0, true).build()
            else -> b.setContentTitle("Starting screen recording").addAction(Notification.Action.Builder(null, "Cancel", pi(ACTION_STOP, 2)).build()).build()
        }
    }
}
