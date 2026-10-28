package com.ajit.dynamicisland.sdk.ai

/** Contract with the on-device AI Engine module (Phase 4). Everything is a Bundle so it can grow. */
object AiContract {
    /** 2: onProgress, R_WAIT and E_BUSY. */
    const val API_VERSION = 2
    const val ENGINE_PACKAGE = "com.ajit.dynamicisland.module.ai"
    const val ACTION_ENGINE = "com.ajit.dynamicisland.action.AI_ENGINE"

    // ---- status() ----
    const val S_READY = "ready"              // Boolean: a model is downloaded and selected
    const val S_MODEL = "model"              // String: display name, e.g. "Qwen3 1.7B"
    const val S_MAX_INPUT_CHARS = "maxInputChars" // Int: rough limit for system + prompt
    const val S_BUSY = "busy"                // Boolean: another request is running

    // ---- generate() request ----
    const val R_SYSTEM = "system"
    const val R_PROMPT = "prompt"
    const val R_MAX_TOKENS = "maxTokens"     // Int, default 512
    const val R_TEMPERATURE = "temperature"  // Float, default 0.3 (notes want facts, not creativity)
    /** Boolean, default true. False: if another app's request is running or waiting, fail at once with E_BUSY instead of queueing. */
    const val R_WAIT = "wait"

    // ---- onProgress() (API 2) ----
    const val P_STAGE = "stage"              // one of STAGE_*
    const val P_AHEAD = "ahead"              // Int: requests ahead of this one (STAGE_QUEUED)
    const val P_MODEL = "model"              // String: model being loaded or used
    const val P_BUSY_WITH = "busyWith"       // String: app whose request is running (STAGE_QUEUED)
    const val STAGE_QUEUED = "queued"
    const val STAGE_LOADING = "loading"      // loading the model into memory (seconds; longer the first time)
    const val STAGE_READING = "reading"      // processing the prompt, before the first word
    const val STAGE_WRITING = "writing"

    // ---- errors ----
    const val E_NO_MODEL = 1
    const val E_NOT_ALLOWED = 2
    const val E_TOO_LONG = 3
    const val E_FAILED = 4
    const val E_CANCELLED = 5
    const val E_UNAVAILABLE = 6               // engine not installed / not trusted / disconnected
    const val E_BUSY = 7                      // R_WAIT=false and another app is using the engine
}
