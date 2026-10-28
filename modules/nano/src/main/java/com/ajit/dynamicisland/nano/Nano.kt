package com.ajit.dynamicisland.nano

import android.app.ActivityManager
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano, built into some phones (Android AICore), through ML Kit's GenAI Prompt API.
 *
 * Important limits (from Google's docs): it only runs while the calling app is the **top
 * foreground app** (not from a background or foreground service: BACKGROUND_USE_BLOCKED),
 * input must be under ~4000 tokens, and AICore enforces a per-app quota. So callers use it
 * when their screen is open and fall back to the AI Engine module otherwise.
 */
object Nano {
    enum class Status { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE, ERROR }

    /** Input token limit documented for the Prompt API, and a pessimistic chars-per-token ratio. */
    const val INPUT_TOKENS = 4000
    fun maxInputChars() = ((INPUT_TOKENS - 120) * 3.0).toInt()

    class Result(val text: String?, val code: Int = 0, val message: String? = null) {
        /** True when another engine should be tried (backgrounded, busy, quota, too long, not ready…). */
        val tryElsewhere get() = text == null && code != GenAiException.ErrorCode.CANCELLED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var model: GenerativeModel? = null
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    @Volatile var lastStatus: Status? = null
        private set

    /** Blocking (call off the main thread). Never throws. */
    fun status(): Status = runCatching {
        runBlocking { withTimeoutOrNull(8000) { client().checkStatus() } }
    }.map { s ->
        when (s) {
            FeatureStatus.AVAILABLE -> Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
            null -> Status.ERROR
            else -> Status.UNAVAILABLE
        }
    }.getOrDefault(Status.UNAVAILABLE).also { lastStatus = it }

    /** Ask the system to download Gemini Nano (it's shared by all apps; no app storage used). */
    fun download(onProgress: (Long) -> Unit, onDone: (String?) -> Unit): Job = scope.launch {
        try {
            client().download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadProgress -> onProgress(st.totalBytesDownloaded)
                    is DownloadStatus.DownloadCompleted -> { lastStatus = Status.AVAILABLE; onDone(null) }
                    is DownloadStatus.DownloadFailed -> onDone(st.e.message ?: "Download failed")
                    else -> {}
                }
            }
        } catch (t: Throwable) { onDone(t.message ?: t.javaClass.simpleName) }
    }

    /** Is this process the top foreground app right now (the only time AICore accepts requests)? */
    fun isForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Generate text, streaming partial text to [onPartial]. Blocking: call off the main thread. */
    fun generateBlocking(system: String, prompt: String, maxTokens: Int, temperature: Float, onPartial: (String) -> Unit = {}): Result {
        if (system.length + prompt.length > maxInputChars()) return Result(null, GenAiException.ErrorCode.REQUEST_TOO_LARGE, "Too long for Gemini Nano")
        return try {
            runBlocking {
                val m = client()
                val sb = StringBuilder()
                val withSystem = runCatching { m.isSystemPromptAvailable() }.getOrDefault(false)
                val text = if (withSystem) prompt else "$system\n\n$prompt"
                val req = if (withSystem) generateContentRequest(SystemInstruction(system), TextPart(text)) { setup(maxTokens, temperature) }
                    else generateContentRequest(TextPart(text)) { setup(maxTokens, temperature) }
                val resp = m.generateContent(req, object : StreamingCallback {
                    override fun onNewText(text: String) { sb.append(text); onPartial(sb.toString().trim()) }
                })
                val out = resp.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: sb.toString()
                if (out.isBlank()) Result(null, GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR, "Gemini Nano returned nothing") else Result(out.trim())
            }
        } catch (e: GenAiException) {
            Result(null, e.errorCode, describe(e.errorCode, e.message))
        } catch (t: Throwable) {
            Result(null, GenAiException.ErrorCode.UNKNOWN, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.setup(maxTokens: Int, temperature: Float) {
        this.temperature = temperature
        this.topK = 16
        this.maxOutputTokens = maxTokens.coerceIn(16, 1024)
    }

    fun describe(code: Int, fallback: String?): String = when (code) {
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "Gemini Nano only works while this app is on screen"
        GenAiException.ErrorCode.BUSY -> "Gemini Nano is busy, try again in a moment"
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "Gemini Nano's daily limit for this app was reached"
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "Too long for Gemini Nano"
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED -> "Gemini Nano isn't available on this phone"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE, GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "Gemini Nano needs a system (AICore) update"
        else -> fallback ?: "Gemini Nano error $code"
    }
}
