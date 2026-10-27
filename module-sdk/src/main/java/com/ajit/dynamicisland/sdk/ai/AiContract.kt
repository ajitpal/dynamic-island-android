package com.ajit.dynamicisland.sdk.ai

/** Contract with the on-device AI Engine module (Phase 4). Everything is a Bundle so it can grow. */
object AiContract {
    const val API_VERSION = 1
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

    // ---- errors ----
    const val E_NO_MODEL = 1
    const val E_NOT_ALLOWED = 2
    const val E_TOO_LONG = 3
    const val E_FAILED = 4
    const val E_CANCELLED = 5
    const val E_UNAVAILABLE = 6               // engine not installed / not trusted / disconnected
}
