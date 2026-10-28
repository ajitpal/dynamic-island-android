package com.ajit.dynamicisland.sdk.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandContract
import java.io.File
import java.util.UUID

/**
 * Talks to the Speech Pack module. Checks that it's the genuine first-party app (signing certificate)
 * before sending it any audio. Callbacks arrive on the main thread.
 *
 * ```
 * SpeechClient(ctx).transcribe(file, "hi", onLine = { start, end, text -> … }, onProgress = { stage, f -> … },
 *     onDone = { … }, onError = { code, msg -> … })
 * ```
 */
class SpeechClient(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: ISpeechEngine? = null
    private var bound = false
    private val waiting = mutableListOf<(ISpeechEngine?) -> Unit>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Int, String) -> Unit>()

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        fun isInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo(SpeechContract.ENGINE_PACKAGE, 0); true } catch (e: Exception) { false }

        /** Installed and signed by a trusted (first-party) certificate. */
        fun isTrusted(ctx: Context): Boolean {
            if (!isInstalled(ctx)) return false
            val pm = ctx.packageManager
            if (IslandContract.TRUSTED_HOST_CERTS.any { hex ->
                    try { pm.hasSigningCertificate(SpeechContract.ENGINE_PACKAGE, HostVerifier.hexToBytes(hex), PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
                }) return true
            return pm.checkSignatures(ctx.packageName, SpeechContract.ENGINE_PACKAGE) == PackageManager.SIGNATURE_MATCH
        }

        fun openApp(ctx: Context): Boolean = try {
            ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(SpeechContract.ENGINE_PACKAGE)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
    }

    private val timeout = Runnable {
        if (engine == null && synchronized(waiting) { waiting.isNotEmpty() }) { unbind(); flush(null) }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            main.removeCallbacks(timeout); engine = ISpeechEngine.Stub.asInterface(service); flush(engine)
        }
        override fun onServiceDisconnected(name: ComponentName?) { engine = null; failPending() }
        override fun onBindingDied(name: ComponentName?) { engine = null; failPending(); unbind() }
        override fun onNullBinding(name: ComponentName?) { flush(null); unbind() }
    }

    private fun failPending() {
        val list = pending.values.toList(); pending.clear()
        main.post { list.forEach { it(SpeechContract.E_UNAVAILABLE, "Speech Pack stopped (the phone may be low on memory). Try again.") } }
    }

    private fun flush(e: ISpeechEngine?) { val l = synchronized(waiting) { waiting.toList().also { waiting.clear() } }; l.forEach { it(e) } }

    /** Main thread. Gives null if the Speech Pack isn't installed, isn't trusted, or doesn't answer in time. */
    fun connect(onReady: (ISpeechEngine?) -> Unit) {
        engine?.let { if (it.asBinder().isBinderAlive) { onReady(it); return } else engine = null }
        if (!isTrusted(ctx)) { onReady(null); return }
        synchronized(waiting) { waiting += onReady }
        if (!bound) {
            bound = try { ctx.bindService(Intent(SpeechContract.ACTION_ENGINE).setPackage(SpeechContract.ENGINE_PACKAGE), conn, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
            if (!bound) { flush(null); return }
        }
        main.removeCallbacks(timeout); main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    /** Status (S_* keys) or null. Main thread; [then] on the main thread. */
    fun status(then: (Bundle?) -> Unit) = connect { e -> then(e?.let { runCatching { it.status() }.getOrNull() }) }

    fun transcribe(
        audio: File, language: String = "hi",
        onLine: (Long, Long, String) -> Unit, onProgress: (String, Float) -> Unit = { _, _ -> },
        onDone: () -> Unit, onError: (Int, String) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        connect { e ->
            if (e == null) { onError(SpeechContract.E_UNAVAILABLE, "Speech Pack isn't installed or didn't respond"); return@connect }
            pending[id] = onError
            val cb = object : ISpeechCallback.Stub() {
                override fun onLine(requestId: String?, startMs: Long, endMs: Long, text: String?) { main.post { if (pending.containsKey(id)) onLine(startMs, endMs, text.orEmpty()) } }
                override fun onProgress(requestId: String?, progress: Bundle?) {
                    val st = progress?.getString(SpeechContract.P_STAGE) ?: SpeechContract.STAGE_TRANSCRIBING
                    val f = progress?.getFloat(SpeechContract.P_FRACTION, 0f) ?: 0f
                    main.post { if (pending.containsKey(id)) onProgress(st, f) }
                }
                override fun onDone(requestId: String?) { main.post { if (pending.remove(id) != null) onDone() } }
                override fun onError(requestId: String?, code: Int, message: String?) { main.post { if (pending.remove(id) != null) onError(code, message ?: "Error $code") } }
            }
            try {
                ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    e.transcribe(id, pfd, Bundle().apply { putString(SpeechContract.R_LANGUAGE, language) }, cb)
                }
            } catch (ex: Exception) { pending.remove(id); onError(SpeechContract.E_UNAVAILABLE, ex.message ?: "Speech Pack disconnected") }
        }
        return id
    }

    fun cancel(id: String) { pending.remove(id); runCatching { engine?.cancel(id) } }

    private fun unbind() { if (bound) { runCatching { ctx.unbindService(conn) }; bound = false } }
    fun close() { main.removeCallbacks(timeout); pending.clear(); engine = null; unbind() }
}
