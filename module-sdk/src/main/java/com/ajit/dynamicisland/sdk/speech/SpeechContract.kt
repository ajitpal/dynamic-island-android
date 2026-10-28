package com.ajit.dynamicisland.sdk.speech

/** Contract with the Speech Pack module (Hindi / Hinglish speech-to-text on the phone). Bundles so it can grow. */
object SpeechContract {
    const val API_VERSION = 1
    const val ENGINE_PACKAGE = "com.ajit.dynamicisland.module.speech"
    const val ACTION_ENGINE = "com.ajit.dynamicisland.action.SPEECH_ENGINE"

    // ---- status() ----
    const val S_READY = "ready"            // Boolean: a model is downloaded
    const val S_MODEL = "model"            // String: display name
    const val S_LANGUAGES = "languages"    // String[]: BCP-47 tags it writes well, e.g. "hi", "en"

    // ---- transcribe() options ----
    const val R_LANGUAGE = "language"      // "hi" (Devanagari + Latin for English words) — the default

    // ---- onProgress() ----
    const val P_STAGE = "stage"            // STAGE_*
    const val P_FRACTION = "fraction"      // Float 0..1 of the audio processed
    const val STAGE_LOADING = "loading"
    const val STAGE_TRANSCRIBING = "transcribing"

    // ---- errors ----
    const val E_NO_MODEL = 1
    const val E_NOT_ALLOWED = 2
    const val E_BAD_AUDIO = 3
    const val E_FAILED = 4
    const val E_CANCELLED = 5
    const val E_UNAVAILABLE = 6
    const val E_BUSY = 7
}
