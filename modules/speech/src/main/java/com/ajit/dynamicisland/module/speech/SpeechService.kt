package com.ajit.dynamicisland.module.speech

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.speech.ISpeechCallback
import com.ajit.dynamicisland.sdk.speech.ISpeechEngine
import com.ajit.dynamicisland.sdk.speech.SpeechContract

/** The engine Meeting Notes binds to. Only Dynamic Island's first-party apps may use it. */
class SpeechService : Service() {
    private val binder = object : ISpeechEngine.Stub() {
        override fun apiVersion() = SpeechContract.API_VERSION

        override fun status(): Bundle {
            if (caller() == null) return Bundle()
            val m = Models.active(this@SpeechService)
            return Bundle().apply {
                putBoolean(SpeechContract.S_READY, m != null)
                putString(SpeechContract.S_MODEL, m?.let { "Omnilingual ${it.label}" })
                putStringArray(SpeechContract.S_LANGUAGES, arrayOf("hi", "en"))
            }
        }

        override fun transcribe(requestId: String?, audio: ParcelFileDescriptor?, options: Bundle?, cb: ISpeechCallback?) {
            if (requestId == null || audio == null || cb == null) { runCatching { audio?.close() }; return }
            val who = caller() ?: run { runCatching { audio.close(); cb.onError(requestId, SpeechContract.E_NOT_ALLOWED, "This app isn't allowed to use Speech Pack") }; return }
            Usage.record(this@SpeechService, who)
            // The descriptor is only valid until this call returns; keep our own copy.
            val fd = audio.dup().also { runCatching { audio.close() } }
            Transcriber.submit(this@SpeechService, requestId, fd, object : Transcriber.Listener {
                override fun line(startMs: Long, endMs: Long, text: String) { runCatching { cb.onLine(requestId, startMs, endMs, text) } }
                override fun progress(stage: String, fraction: Float) { runCatching { cb.onProgress(requestId, Bundle().apply { putString(SpeechContract.P_STAGE, stage); putFloat(SpeechContract.P_FRACTION, fraction) }) } }
                override fun done() { runCatching { cb.onDone(requestId) } }
                override fun error(code: Int, message: String) { runCatching { cb.onError(requestId, code, message) } }
            })
        }

        override fun cancel(requestId: String?) { if (requestId != null && caller() != null) Transcriber.cancel(requestId) }
    }

    private fun caller(): String? {
        val uid = Binder.getCallingUid()
        return Callers.allowed(this, uid).also { if (it == null) Log.w("SpeechPack", "Rejected uid $uid") }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

object Callers {
    /** Same signing key as this app, or a trusted Dynamic Island release key. */
    fun allowed(ctx: Context, uid: Int): String? {
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return null
        return pkgs.firstOrNull { p ->
            pm.checkSignatures(ctx.packageName, p) == PackageManager.SIGNATURE_MATCH ||
                IslandContract.TRUSTED_HOST_CERTS.any { hex ->
                    try { pm.hasSigningCertificate(p, HostVerifier.hexToBytes(hex), PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
                }
        }
    }
}

/** Which apps used Speech Pack recently (shown on its screen; nothing else is kept). */
object Usage {
    fun record(ctx: Context, pkg: String) = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putLong(pkg, System.currentTimeMillis()).apply()
    fun all(ctx: Context): Map<String, Long> = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
}
