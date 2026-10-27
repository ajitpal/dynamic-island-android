package com.ajit.dynamicisland.sdk

/**
 * The wire contract between the Dynamic Island app ("host") and add-on modules.
 * Everything crosses process boundaries as Bundles so the contract can evolve without breaking modules:
 * new keys are optional, unknown keys are ignored.
 */
object IslandContract {
    const val API_VERSION = 1

    const val HOST_PACKAGE = "com.ajit.dynamicisland"
    /** Intent action a module's service must declare. */
    const val ACTION_MODULE = "com.ajit.dynamicisland.action.MODULE"
    /** Permission the host defines with protectionLevel="signature|knownSigner" (approved developers). */
    const val PERMISSION_MODULE_API = "com.ajit.dynamicisland.permission.MODULE_API"
    /** Signature permission only the genuine Dynamic Island app holds. Put it on any activity the island opens
     *  for you (`android:permission="com.ajit.dynamicisland.permission.HOST"`) so other apps can't start it. */
    const val PERMISSION_HOST = "com.ajit.dynamicisland.permission.HOST"

    /** Optional <meta-data> on the module service. */
    const val META_DESCRIPTION = "com.ajit.dynamicisland.module.DESCRIPTION"
    const val META_SETTINGS_ACTIVITY = "com.ajit.dynamicisland.module.SETTINGS_ACTIVITY"
    const val META_DEVELOPER = "com.ajit.dynamicisland.module.DEVELOPER"
    /** Development only: accept any host build (e.g. a debug-signed Dynamic Island). */
    const val META_TRUST_ANY_HOST = "com.ajit.dynamicisland.module.TRUST_ANY_HOST"

    /**
     * SHA-256 digests of Dynamic Island release signing certificates. A module only talks to a host
     * signed with one of these (unless META_TRUST_ANY_HOST is set for development).
     */
    val TRUSTED_HOST_CERTS = setOf(
        "12178c6a653dd8e19c2414b0f4375270706820c14b0fc42bc5137c44d5416501",
    )

    // ---- describe() ----
    const val K_TOOLS = "tools"            // ArrayList<Bundle>
    const val K_TOOL_ID = "id"
    const val K_TOOL_LABEL = "label"
    const val K_TOOL_ICON = "icon"          // android.graphics.drawable.Icon
    /** Optional: fully-qualified class of an exported activity in the module that the island opens on tap
     *  (instead of calling onToolTapped). Needed for anything that must start from a visible screen, e.g. the microphone. */
    const val K_TOOL_ACTIVITY = "activity"

    // ---- card ----
    const val K_ID = "id"                   // String, unique within the module
    const val K_STYLE = "style"             // STYLE_*
    const val K_TITLE = "title"             // CharSequence (required)
    const val K_TEXT = "text"               // CharSequence
    const val K_COMPACT = "compact"         // short text shown right of the camera
    const val K_ICON = "icon"               // Icon
    const val K_TINT_ICON = "tintIcon"      // Boolean: tint a monochrome icon with the accent (default true)
    const val K_ACCENT = "accent"           // Int ARGB
    const val K_PROGRESS = "progress"       // Float 0..1 (omit for none)
    const val K_COUNTDOWN_TO = "countdownTo" // Long wall-clock ms
    const val K_COUNT_UP_FROM = "countUpFrom" // Long wall-clock ms
    const val K_PEEK = "peek"               // Boolean: pop the island open
    const val K_PEEK_MS = "peekMs"          // Long
    const val K_EXPIRES_AT = "expiresAt"    // Long wall-clock ms
    const val K_VERSION = "version"         // Int: bump to signal "new content" (re-peek)
    const val K_TAP_URL = "tapUrl"          // String: open this URL / content URI on tap
    const val K_TAP_CALLBACK = "tapCallback" // Boolean: send onAction(cardId, ACTION_TAP) on tap
    const val K_TAP_ACTIVITY = "tapActivity" // String: class of an exported activity in the module to open on tap
    const val K_TAP_ARG = "tapArg"          // String passed to that activity as EXTRA_ARG
    const val K_ACTIONS = "actions"         // ArrayList<Bundle>, max 3

    const val STYLE_LIVE = "live"
    const val STYLE_TIMER = "timer"
    const val STYLE_PROGRESS = "progress"
    const val STYLE_ALERT = "alert"

    // ---- action ----
    const val A_ID = "id"
    const val A_TITLE = "title"
    const val A_STYLE = "style"             // 0 normal, 1 positive, 2 negative
    const val A_URL = "url"                 // String: host opens it
    const val A_INPUT = "input"             // Boolean: show a text field; text arrives in onAction
    const val A_HINT = "hint"
    const val A_ACTIVITY = "activity"       // String: class of an exported activity in the module to open
    const val A_ARG = "arg"                 // String passed to that activity as EXTRA_ARG

    /** Extra the island puts on module activities it opens. */
    const val EXTRA_ARG = "com.ajit.dynamicisland.ARG"

    const val ACTION_TAP = "__tap"

    /** Limits the host enforces. */
    const val MAX_CARDS_PER_MODULE = 3
    const val MAX_TITLE = 80
    const val MAX_TEXT = 400
}
