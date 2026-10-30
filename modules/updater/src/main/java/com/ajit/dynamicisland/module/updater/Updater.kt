package com.ajit.dynamicisland.module.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandContract
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks GitHub Releases, downloads, verifies and installs updates for the island and its modules,
 * one at a time. Android always asks the person to confirm an install from an app like this one.
 *
 * Everything on the main thread except network and file work, which runs on [worker].
 */
object Updater {
    enum class Phase { WAITING, DOWNLOADING, VERIFYING, CONFIRM, INSTALLING, DONE, FAILED }

    /** [canRetry] is false when trying again can't help (e.g. the installed copy is signed with another key). */
    data class Item(val update: Update, val phase: Phase = Phase.WAITING, val progress: Float = 0f, val error: String? = null, val canRetry: Boolean = true) {
        /** Still something the person can install from here. */
        val actionable get() = phase != Phase.DONE && (phase != Phase.FAILED || canRetry)
    }

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val checkedAt: Long, val release: String?) : State()
        data class Available(val release: Release, val items: List<Item>, val checkedAt: Long) : State()
        data class Failed(val message: String, val checkedAt: Long) : State()
    }

    fun interface Listener { fun onState(s: State) }

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "updater") }
    private val listeners = mutableListOf<Listener>()
    var state: State = State.Idle
        private set

    fun addListener(l: Listener) { listeners += l; l.onState(state) }
    fun removeListener(l: Listener) { listeners -= l }
    private fun set(s: State) { state = s; listeners.toList().forEach { it.onState(s) } }

    val installing get() = (state as? State.Available)?.items?.any { it.phase in setOf(Phase.DOWNLOADING, Phase.VERIFYING, Phase.CONFIRM, Phase.INSTALLING) } == true

    // ------------------------------------------------------------------ checking

    /** Check now (main thread). [then] gets the number of updates, or -1 on failure. */
    fun check(ctx: Context, then: (Int) -> Unit = {}) {
        if (state == State.Checking || installing) return
        val app = ctx.applicationContext
        set(State.Checking)
        worker.execute {
            val result = runCatching { Release.parse(get(Repo.MANIFEST_URL)) }
            // The module catalogue rides along with the daily check (best-effort, never fails the check).
            CatalogSync.sync(app)
            main.post {
                val now = System.currentTimeMillis()
                UPrefs.setLastCheck(app, now)
                result.fold(
                    onSuccess = { r ->
                        val ups = Repo.pending(r) { installedVersion(app, it) }
                        UPrefs.setAvailable(app, ups.size, r.version)
                        set(if (ups.isEmpty()) State.UpToDate(now, r.version) else State.Available(r, ups.map { Item(it) }, now))
                        then(ups.size)
                    },
                    onFailure = { e ->
                        set(State.Failed(friendly(e), now)); then(-1)
                    },
                )
                UpdaterModule.instance?.refreshTile()
            }
        }
    }

    /** Last check's result without the network: for the tile and the screen before a check. */
    fun restore(ctx: Context) {
        if (state != State.Idle) return
        val last = UPrefs.lastCheck(ctx)
        if (last > 0 && UPrefs.availableCount(ctx) == 0) set(State.UpToDate(last, UPrefs.availableRelease(ctx)))
    }

    fun installedVersion(ctx: Context, pkg: String): Pair<Long, String>? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        pi.longVersionCode to (pi.versionName ?: "")
    } catch (e: PackageManager.NameNotFoundException) { null }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Couldn't reach GitHub. Check your internet connection and try again."
        is java.io.FileNotFoundException -> "No release with update information was found yet."
        else -> e.message ?: "Something went wrong."
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DynamicIsland-Updater")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode == 404) throw java.io.FileNotFoundException(url)
            if (c.responseCode !in 200..299) throw IllegalStateException("GitHub answered ${c.responseCode}. Try again later.")
            return c.inputStream.bufferedReader().use { it.readText() }.also { require(it.length < 200_000) { "Update information is too large" } }
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ installing

    /** Install every available update in order, or only [pkg]. Main thread; needs a visible screen for Android's prompts. */
    fun installAll(ctx: Context, pkg: String? = null) {
        val av = state as? State.Available ?: return
        if (installing) return
        val app = ctx.applicationContext
        val queue = av.items.filter { (pkg == null || it.update.app.pkg == pkg) && it.actionable }.map { it.update.app.pkg }
        if (queue.isEmpty()) return
        // Mark the chosen ones as waiting again (after a failure, "Try Again" restarts them).
        set(av.copy(items = av.items.map { if (it.update.app.pkg in queue) it.copy(phase = Phase.WAITING, progress = 0f, error = null) else it }))
        next(app, queue)
    }

    private var queue: List<String> = emptyList()

    private fun next(ctx: Context, remaining: List<String>) {
        queue = remaining
        val pkg = remaining.firstOrNull() ?: run { finished(ctx); return }
        val av = state as? State.Available ?: return
        val item = av.items.firstOrNull { it.update.app.pkg == pkg } ?: run { next(ctx, remaining.drop(1)); return }
        val a = item.update.app
        update(pkg) { it.copy(phase = Phase.DOWNLOADING, progress = 0f) }
        worker.execute {
            val result = runCatching {
                val f = download(ctx, av.release.downloadUrl(a), a) { p -> main.post { update(pkg) { it.copy(progress = p) } } }
                main.post { update(pkg) { it.copy(phase = Phase.VERIFYING) } }
                verify(ctx, f, a)
                f
            }
            main.post {
                result.fold(
                    onSuccess = { f -> update(pkg) { it.copy(phase = Phase.CONFIRM) }; startInstall(ctx, f, a) },
                    onFailure = { e -> fail(ctx, pkg, e.message ?: "Download failed", canRetry = e !is KeyMismatch) },
                )
            }
        }
    }

    private fun download(ctx: Context, url: String, a: Release.App, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, a.file)
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: a.size
            if (total > 300L * 1024 * 1024) throw IllegalStateException("The download is unexpectedly large")
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0 && done - last > 256 * 1024) { last = done; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                }
            } }
        } finally { c.disconnect() }
        progress(1f)
        return out
    }

    /** Checksum from the release, then the APK's signing certificate must match the installed app's and the release key. */
    fun verify(ctx: Context, f: File, a: Release.App) {
        val sha = MessageDigest.getInstance("SHA-256").let { md -> f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest() }
            .joinToString("") { "%02x".format(it) }
        if (sha != a.sha256) { f.delete(); throw SecurityException("The download was damaged or changed (checksum doesn't match). Try again.") }
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("The download isn't a valid app.")
        if (info.packageName != a.pkg) throw SecurityException("The download is a different app than expected.")
        if (info.longVersionCode != a.versionCode) throw SecurityException("The download has an unexpected version.")
        val newCert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sha256(it.toByteArray()) }
            ?: throw SecurityException("The download isn't signed.")
        val installed = pm.getPackageInfo(a.pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()?.let { sha256(it.toByteArray()) }
        if (newCert != installed) throw KeyMismatch("The installed ${a.name} is signed with a different key (for example a test build), so it can't be updated here. Uninstall it and install the release from GitHub.")
        if (IslandContract.TRUSTED_HOST_CERTS.none { HostVerifier.hexToBytes(it).contentEquals(HostVerifier.hexToBytes(newCert)) })
            throw SecurityException("The download isn't signed with the Dynamic Island release key.")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun startInstall(ctx: Context, f: File, a: Release.App) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) { fail(ctx, a.pkg, NEEDS_PERMISSION); return }
        try {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(a.pkg)
                // Skips Android's prompt only where it allows that (apps this Updater installed itself, Android 12+).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                f.inputStream().use { inp -> s.openWrite(a.file, 0, f.length()).use { out -> inp.copyTo(out); s.fsync(out) } }
                val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_PKG, a.pkg),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) { fail(ctx, a.pkg, e.message ?: "Couldn't start the install") }
    }

    /** From [InstallResultReceiver]. */
    fun onInstallResult(ctx: Context, pkg: String, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                update(pkg) { it.copy(phase = Phase.CONFIRM) }
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                update(pkg) { it.copy(phase = Phase.DONE, progress = 1f) }
                File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() }
                next(ctx, queue.drop(1))
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(ctx, pkg, "Install cancelled.")
            else -> fail(ctx, pkg, message ?: "Android couldn't install it (error $status).")
        }
    }

    /** The installed app was signed with another key: Android would refuse the update, so don't offer to retry. */
    class KeyMismatch(msg: String) : SecurityException(msg)

    private fun fail(ctx: Context, pkg: String, msg: String, canRetry: Boolean = true) {
        update(pkg) { it.copy(phase = Phase.FAILED, error = msg, canRetry = canRetry) }
        // Stop here: later items often depend on earlier ones, and the person should see what happened.
        queue = emptyList()
        finished(ctx)
    }

    private fun finished(ctx: Context) {
        val av = state as? State.Available ?: return
        val left = av.items.count { it.actionable }
        UPrefs.setAvailable(ctx, left, av.release.version)
        if (left == 0) set(State.UpToDate(System.currentTimeMillis(), av.release.version))
        UpdaterModule.instance?.refreshTile()
    }

    private fun update(pkg: String, f: (Item) -> Item) {
        val av = state as? State.Available ?: return
        set(av.copy(items = av.items.map { if (it.update.app.pkg == pkg) f(it) else it }))
    }

    const val EXTRA_PKG = "pkg"
    const val NEEDS_PERMISSION = "Allow Updater to install apps, then try again."

    /** Tests and screenshots only. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(s: State) = set(s)

    /** Tests only. */
    fun resetForTest() { set(State.Idle); queue = emptyList(); listeners.clear() }
}
