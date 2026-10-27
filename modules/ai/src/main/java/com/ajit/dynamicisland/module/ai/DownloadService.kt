package com.ajit.dynamicisland.module.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a model in the foreground (it's large), resumable, and verifies its SHA-256. */
class DownloadService : Service() {
    companion object {
        private const val EXTRA_MODEL = "model"
        private const val CHANNEL = "download"
        private const val NOTIF = 11
        private const val CARD = "download"

        @Volatile var current: Model? = null; private set
        @Volatile var done: Long = 0; private set
        @Volatile var phase: String = ""; private set
        @Volatile var lastError: String? = null; private set
        val listeners = mutableSetOf<() -> Unit>()
        @Volatile private var cancel = false

        fun start(ctx: Context, m: Model) {
            lastError = null
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_MODEL, m.name))
        }
        fun cancel() { cancel = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastUi = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = intent?.getStringExtra(EXTRA_MODEL)?.let { runCatching { Model.valueOf(it) }.getOrNull() }
        if (m == null || current != null) { if (current == null) stopSelf(); return START_NOT_STICKY }
        current = m; done = Models.partFile(this, m).takeIf { it.exists() }?.length() ?: 0; phase = "Downloading"; cancel = false
        channel()
        startForeground(NOTIF, notification(m), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Thread({ work(m) }, "model-download").start()
        return START_NOT_STICKY
    }

    private fun work(m: Model) {
        var err: String? = null
        try {
            if (Models.freeBytes(this) < (m.bytes - done) + 200_000_000L) throw IOException("Not enough free storage (needs about ${(m.bytes - done) / 1_000_000} MB)")
            download(m)
            phase = "Checking"; ui(m, force = true)
            val part = Models.partFile(this, m)
            if (sha256(part) != m.sha256) { part.delete(); throw IOException("The file didn't match its checksum, so it was deleted. Please try again.") }
            if (!part.renameTo(Models.file(this, m))) throw IOException("Couldn't save the model")
            if (Models.active(this) == null || !Models.isPresent(this, Models.selected(this))) Models.select(this, m)
        } catch (t: Throwable) {
            Log.w("AiDownload", "failed", t)
            err = if (cancel) null else (t.message ?: t.javaClass.simpleName)
        }
        main.post { finish(m, err) }
    }

    private fun download(m: Model) {
        val part = Models.partFile(this, m)
        var url = URL(m.url)
        var conn: HttpURLConnection
        var hops = 0
        // Follow redirects ourselves so the Range header survives the jump to the CDN.
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "DynamicIsland-AIEngine/1.0")
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            val code = conn.responseCode
            if (code in 300..399) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); if (++hops > 6) throw IOException("Too many redirects"); continue }
            if (code == 416) { done = part.length(); return } // already complete
            if (code != 200 && code != 206) throw IOException("Server said $code")
            if (code == 200) { done = 0 } // server ignored the range: start over
            break
        }
        conn.inputStream.use { input ->
            FileOutputStream(part, done > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancel) throw IOException("Cancelled")
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    ui(m)
                }
            }
        }
        if (part.length() != m.bytes) throw IOException("Download incomplete (${part.length() / 1_000_000} of ${m.bytes / 1_000_000} MB). Tap Download to resume.")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ui(m: Model, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUi < 1000) return
        lastUi = now
        main.post {
            getSystemService(NotificationManager::class.java).notify(NOTIF, notification(m))
            IslandModuleService.current?.island?.post(
                IslandCard.Builder(this, CARD).title("AI model · ${(done * 100 / m.bytes)}%").text("$phase ${m.label}")
                    .icon(R.drawable.ic_spark).accent(0xFF5E5CE6.toInt()).progress(done.toFloat() / m.bytes)
                    .style(com.ajit.dynamicisland.sdk.IslandContract.STYLE_PROGRESS).build()
            )
            listeners.toList().forEach { it() }
        }
    }

    private fun finish(m: Model, err: String?) {
        current = null; lastError = err
        IslandModuleService.current?.island?.let { island ->
            island.remove(CARD)
            if (err == null && !cancel) island.post(IslandCard.Builder(this, "ready").title("AI model ready").text("${m.label} works offline now")
                .icon(R.drawable.ic_spark).accent(0xFF30D158.toInt()).peek(4000).expiresIn(20_000).build())
        }
        listeners.toList().forEach { it() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(m: Model): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pct = (done * 100 / m.bytes).toInt()
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("$phase ${m.label}").setContentText("${done / 1_000_000} / ${m.bytes / 1_000_000} MB")
            .setProgress(100, pct, phase == "Checking").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build()
    }
}
