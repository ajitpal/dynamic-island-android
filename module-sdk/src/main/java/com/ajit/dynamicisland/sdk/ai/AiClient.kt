package com.ajit.dynamicisland.sdk.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandContract
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to the AI Engine module. Verifies that the engine is the genuine first-party app (signing
 * certificate pinned, same as the island) before sending it anything.
 *
 * ```
 * val ai = AiClient(context)
 * ai.generate(system = "You write meeting notes.", prompt = transcript,
 *     onPartial = { show(it) }, onDone = { save(it) }, onError = { code, msg -> … })
 * …
 * ai.close()
 * ```
 * Callbacks arrive on the main thread. [generateBlocking] is for worker threads.
 */
class AiClient(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: IAiEngine? = null
    private var bound = false
    private val waiting = mutableListOf<(IAiEngine?) -> Unit>()
    /** Error callbacks of requests still running, fired if the engine process dies. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Int, String) -> Unit>()

    class Result(val text: String?, val errorCode: Int = 0, val error: String? = null)

    companion object {
        fun isInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo(AiContract.ENGINE_PACKAGE, 0); true } catch (e: Exception) { false }

        /** Installed and signed by a trusted (first-party) certificate. */
        fun isTrusted(ctx: Context): Boolean {
            if (!isInstalled(ctx)) return false
            val pm = ctx.packageManager
            if (IslandContract.TRUSTED_HOST_CERTS.any { hex ->
                    try { pm.hasSigningCertificate(AiContract.ENGINE_PACKAGE, HostVerifier.hexToBytes(hex), PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
                }) return true
            // Development: an engine signed with the same key as this app (e.g. both debug builds).
            return pm.checkSignatures(ctx.packageName, AiContract.ENGINE_PACKAGE) == PackageManager.SIGNATURE_MATCH
        }

        /** Open the AI Engine app (to download a model). */
        fun openEngineApp(ctx: Context): Boolean = try {
            ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(AiContract.ENGINE_PACKAGE)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = IAiEngine.Stub.asInterface(service)
            flush(engine)
        }
        override fun onServiceDisconnected(name: ComponentName?) { engine = null; failPending() }
        override fun onBindingDied(name: ComponentName?) { engine = null; failPending(); unbind() }
        override fun onNullBinding(name: ComponentName?) { flush(null); unbind() }
    }

    private fun failPending() {
        val list = pending.values.toList(); pending.clear()
        main.post { list.forEach { it(AiContract.E_UNAVAILABLE, "The AI Engine stopped (the phone may be low on memory). Try again or pick a smaller model.") } }
    }

    private fun flush(e: IAiEngine?) {
        val list = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
        list.forEach { it(e) }
    }

    /** Connect (if needed) and hand over the engine, or null if it isn't available. Main thread. */
    fun connect(onReady: (IAiEngine?) -> Unit) {
        engine?.let { onReady(it); return }
        if (!isTrusted(ctx)) { onReady(null); return }
        synchronized(waiting) { waiting += onReady }
        if (!bound) {
            bound = try {
                ctx.bindService(Intent(AiContract.ACTION_ENGINE).setPackage(AiContract.ENGINE_PACKAGE), conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) { false }
            if (!bound) flush(null)
        }
    }

    /** Status of the engine, or null if not available. Blocking: call off the main thread. */
    fun statusBlocking(timeoutMs: Long = 5000): Bundle? {
        val e = awaitEngine(timeoutMs) ?: return null
        return try { e.status() } catch (ex: Exception) { null }
    }

    fun generate(
        system: String, prompt: String, maxTokens: Int = 512, temperature: Float = 0.3f,
        onPartial: (String) -> Unit = {}, onDone: (String) -> Unit, onError: (Int, String) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        connect { e ->
            if (e == null) { onError(AiContract.E_UNAVAILABLE, "AI Engine isn't installed or isn't trusted"); return@connect }
            pending[id] = onError
            val cb = object : IAiCallback.Stub() {
                override fun onPartial(requestId: String?, textSoFar: String?) { main.post { if (pending.containsKey(id)) onPartial(textSoFar.orEmpty()) } }
                override fun onDone(requestId: String?, text: String?) { main.post { if (pending.remove(id) != null) onDone(text.orEmpty()) } }
                override fun onError(requestId: String?, code: Int, message: String?) { main.post { if (pending.remove(id) != null) onError(code, message ?: "Error $code") } }
            }
            try { e.generate(id, request(system, prompt, maxTokens, temperature), cb) }
            catch (ex: Exception) { pending.remove(id); onError(AiContract.E_UNAVAILABLE, ex.message ?: "AI Engine disconnected") }
        }
        return id
    }

    /** Runs a request and waits for the answer. Never call on the main thread. */
    fun generateBlocking(system: String, prompt: String, maxTokens: Int = 512, temperature: Float = 0.3f,
                         timeoutMs: Long = 10 * 60_000L, onPartial: (String) -> Unit = {},
                         isCancelled: () -> Boolean = { false }): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "generateBlocking on the main thread" }
        val e = awaitEngine(10_000) ?: return Result(null, AiContract.E_UNAVAILABLE, "AI Engine isn't installed or isn't trusted")
        val latch = CountDownLatch(1)
        var result = Result(null, AiContract.E_FAILED, "Timed out")
        val id = UUID.randomUUID().toString()
        val cb = object : IAiCallback.Stub() {
            override fun onPartial(requestId: String?, textSoFar: String?) { onPartial(textSoFar.orEmpty()) }
            override fun onDone(requestId: String?, text: String?) { result = Result(text.orEmpty()); latch.countDown() }
            override fun onError(requestId: String?, code: Int, message: String?) { result = Result(null, code, message); latch.countDown() }
        }
        try { e.generate(id, request(system, prompt, maxTokens, temperature), cb) } catch (ex: Exception) {
            return Result(null, AiContract.E_UNAVAILABLE, ex.message)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!latch.await(300, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) { runCatching { e.cancel(id) }; return Result(null, AiContract.E_CANCELLED, "Cancelled") }
            if (engine == null || !e.asBinder().isBinderAlive) return Result(null, AiContract.E_UNAVAILABLE, "The AI Engine stopped (the phone may be low on memory). Try again or pick a smaller model.")
            if (System.currentTimeMillis() > deadline) { runCatching { e.cancel(id) }; break }
        }
        return result
    }

    /** Cancel a request. Its callbacks won't be called any more. */
    fun cancel(requestId: String) { pending.remove(requestId); runCatching { engine?.cancel(requestId) } }

    private fun awaitEngine(timeoutMs: Long): IAiEngine? {
        engine?.let { return it }
        val latch = CountDownLatch(1)
        var got: IAiEngine? = null
        main.post { connect { got = it; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return got
    }

    private fun request(system: String, prompt: String, maxTokens: Int, temperature: Float) = Bundle().apply {
        putString(AiContract.R_SYSTEM, system); putString(AiContract.R_PROMPT, prompt)
        putInt(AiContract.R_MAX_TOKENS, maxTokens); putFloat(AiContract.R_TEMPERATURE, temperature)
    }

    private fun unbind() { if (bound) { runCatching { ctx.unbindService(conn) }; bound = false } }

    fun close() { pending.clear(); engine = null; unbind() }
}
