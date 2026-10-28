package com.ajit.dynamicisland.module.voice

import android.content.Context
import com.ajit.dynamicisland.nano.Nano
import com.ajit.dynamicisland.sdk.ai.AiClient
import com.ajit.dynamicisland.sdk.ai.AiContract

/**
 * Chooses where a request runs: Gemini Nano (built into some phones) while Meeting Notes is on
 * screen, if the user turned it on; otherwise, or if Nano refuses, the AI Engine module.
 * Blocking: call off the main thread.
 */
class Brain(private val ctx: Context) {
    private val engine = AiClient(ctx)
    /** Which engines answered (for the "written by" line). */
    val usedBy = linkedSetOf<String>()

    class Out(val text: String?, val code: Int = 0, val error: String? = null)

    private val nanoOn get() = VoicePrefs.useNano(ctx) && (Nano.lastStatus ?: Nano.status()) == Nano.Status.AVAILABLE

    /** What can run right now, and how much text one request may hold. Null = nothing available. */
    fun capacity(): Pair<Int, String>? {
        val st = if (AiClient.isTrusted(ctx)) engine.statusBlocking() else null
        val engineReady = st?.getBoolean(AiContract.S_READY) == true
        val engineMax = st?.getInt(AiContract.S_MAX_INPUT_CHARS, 9000) ?: 9000
        return when {
            engineReady && nanoOn -> minOf(engineMax, Nano.maxInputChars()) to "${st!!.getString(AiContract.S_MODEL)}"
            engineReady -> engineMax to "${st!!.getString(AiContract.S_MODEL)}"
            nanoOn -> Nano.maxInputChars() to "Gemini Nano"
            else -> null
        }
    }

    fun generate(system: String, prompt: String, maxTokens: Int, temperature: Float,
                 onPartial: (String) -> Unit = {}, isCancelled: () -> Boolean = { false }): Out {
        if (nanoOn && Nano.isForeground()) {
            val r = Nano.generateBlocking(system, prompt, maxTokens, temperature, onPartial)
            if (r.text != null) { usedBy += "Gemini Nano"; return Out(r.text) }
            if (!r.tryElsewhere) return Out(null, AiContract.E_CANCELLED, r.message)
            if (!AiClient.isTrusted(ctx)) return Out(null, AiContract.E_UNAVAILABLE,
                (r.message ?: "Gemini Nano couldn't answer") + ". Keep Meeting Notes open, or install the AI Engine module.")
        }
        if (!AiClient.isTrusted(ctx)) return Out(null, AiContract.E_UNAVAILABLE,
            if (VoicePrefs.useNano(ctx) && Nano.lastStatus == Nano.Status.AVAILABLE) "Gemini Nano only works while Meeting Notes is on screen. Install the AI Engine module for background notes."
            else "Install the AI Engine module to use AI")
        val r = engine.generateBlocking(system, prompt, maxTokens, temperature, onPartial = onPartial, isCancelled = isCancelled)
        if (r.text != null) engine.statusBlocking()?.getString(AiContract.S_MODEL)?.let { usedBy += it }
        return Out(r.text, r.errorCode, r.error)
    }

    fun close() = engine.close()

    companion object {
        /** Is any AI available to this app (without blocking on the engine)? */
        fun anyInstalled(ctx: Context) = AiClient.isTrusted(ctx) || (VoicePrefs.useNano(ctx) && Nano.lastStatus == Nano.Status.AVAILABLE)
    }
}
