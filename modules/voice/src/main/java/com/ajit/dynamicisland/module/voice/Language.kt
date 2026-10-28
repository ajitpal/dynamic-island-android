package com.ajit.dynamicisland.module.voice

/** What people speak in the meeting. */
enum class Lang(val label: String, val native: String, val tag: String) {
    EN("English", "English", "en"),
    HI("Hindi", "हिन्दी", "hi"),
    HINGLISH("Hinglish", "Hindi + English", "hi");

    val isHindi get() = this != EN
}

/** Whether Android's own on-device recognizer can do Hindi on this phone. */
enum class AndroidHindi { READY, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

/**
 * Which engine transcribes a meeting. English: Moonshine, live. Hindi/Hinglish: Android's on-device
 * recognizer when the phone has offline Hindi (live captions), otherwise the Speech Pack module after
 * the recording (more accurate on many phones, but not live). Pure Kotlin, unit-tested.
 */
sealed class Plan {
    object MoonshineLive : Plan()
    object AndroidLive : Plan()
    object SpeechPackAfter : Plan()
    /** Nothing can transcribe this language yet; [androidCanDownload] / [speechPackInstalled] say what the person can do. */
    data class NeedsSetup(val androidCanDownload: Boolean, val speechPackInstalled: Boolean) : Plan()

    companion object {
        fun choose(lang: Lang, android: AndroidHindi, speechPackReady: Boolean, speechPackInstalled: Boolean, preferSpeechPack: Boolean): Plan = when {
            !lang.isHindi -> MoonshineLive
            preferSpeechPack && speechPackReady -> SpeechPackAfter
            android == AndroidHindi.READY -> AndroidLive
            speechPackReady -> SpeechPackAfter
            else -> NeedsSetup(android == AndroidHindi.DOWNLOADABLE, speechPackInstalled)
        }
    }
}
