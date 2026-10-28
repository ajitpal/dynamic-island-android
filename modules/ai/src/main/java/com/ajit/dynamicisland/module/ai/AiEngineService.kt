package com.ajit.dynamicisland.module.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.ai.AiContract
import com.ajit.dynamicisland.sdk.ai.IAiCallback
import com.ajit.dynamicisland.sdk.ai.IAiEngine

/** The engine other apps bind to. Only the Dynamic Island app and its first-party modules may use it. */
class AiEngineService : Service() {

    private val binder = object : IAiEngine.Stub() {
        override fun apiVersion() = AiContract.API_VERSION

        override fun status(): Bundle {
            if (caller() == null) return Bundle()
            val m = Models.active(this@AiEngineService)
            return Bundle().apply {
                putBoolean(AiContract.S_READY, m != null)
                putString(AiContract.S_MODEL, m?.label)
                putInt(AiContract.S_MAX_INPUT_CHARS, Text.maxInputChars(m?.contextTokens ?: 4096, 512))
                putBoolean(AiContract.S_BUSY, LlmEngine.busy)
            }
        }

        override fun generate(requestId: String?, request: Bundle?, cb: IAiCallback?) {
            if (requestId == null || request == null || cb == null) return
            val who = caller() ?: run { runCatching { cb.onError(requestId, AiContract.E_NOT_ALLOWED, "This app isn't allowed to use AI Engine") }; return }
            Usage.record(this@AiEngineService, who)
            LlmEngine.submit(
                this@AiEngineService, requestId,
                request.getString(AiContract.R_SYSTEM).orEmpty(), request.getString(AiContract.R_PROMPT).orEmpty(),
                request.getInt(AiContract.R_MAX_TOKENS, 512), request.getFloat(AiContract.R_TEMPERATURE, 0.3f),
                object : LlmEngine.Listener {
                    override fun onPartial(text: String) { runCatching { cb.onPartial(requestId, text) } }
                    override fun onDone(text: String) { runCatching { cb.onDone(requestId, text) } }
                    override fun onError(code: Int, message: String) { runCatching { cb.onError(requestId, code, message) } }
                    // Older clients don't know this call; a oneway call they don't implement is simply ignored.
                    override fun onProgress(p: Bundle) { runCatching { cb.onProgress(requestId, p) } }
                },
                caller = who, wait = request.getBoolean(AiContract.R_WAIT, true),
            )
        }

        override fun cancel(requestId: String?) { if (requestId != null && caller() != null) LlmEngine.cancel(requestId) }
    }

    /** Package name of an allowed caller, or null. */
    private fun caller(): String? {
        val uid = Binder.getCallingUid()
        val ok = Callers.allowed(this, uid)
        if (ok == null) Log.w("AiEngine", "Rejected uid $uid")
        return ok
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

object Callers {
    /** Same signing key as this engine, or a trusted Dynamic Island release key. */
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

/** Which apps used the engine recently (shown on the main screen, nothing else is kept). */
object Usage {
    fun record(ctx: Context, pkg: String) = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putLong(pkg, System.currentTimeMillis()).apply()
    fun all(ctx: Context): Map<String, Long> = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
}
