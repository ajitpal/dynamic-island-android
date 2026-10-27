package com.ajit.dynamicisland.core.tools

import com.ajit.dynamicisland.R

/** What a tool needs beyond the core permissions. */
enum class ToolNeed { NONE, DND_ACCESS }

/**
 * A tool that can be placed in the island's tools panel.
 * Ids are stable strings saved in settings: "torch", "timer:5", "app:com.whatsapp" …
 * Phase 2 adds tools contributed by add-on modules ("mod:<module>/<tool>").
 */
class ToolDef(
    val id: String,
    val label: String,
    val icon: Int,
    val category: String,
    val need: ToolNeed = ToolNeed.NONE,
    val activeLabel: String? = null,
)

object ToolCatalog {
    const val APP_PREFIX = "app:"
    const val TIMER_PREFIX = "timer:"

    val builtIn: List<ToolDef> = listOf(
        ToolDef("torch", "Flashlight", R.drawable.ic_torch, "Everyday", activeLabel = "Light off"),
        ToolDef("screenshot", "Screenshot", R.drawable.ic_screenshot, "Everyday"),
        ToolDef("note", "Pin note", R.drawable.ic_note, "Everyday"),
        ToolDef("inbox", "Inbox", R.drawable.ic_inbox, "Everyday"),
        ToolDef("otp", "Copy OTP", R.drawable.ic_key, "Everyday"),
        ToolDef("timer:1", "Timer 1m", R.drawable.ic_timer, "Timers"),
        ToolDef("timer:3", "Timer 3m", R.drawable.ic_timer, "Timers"),
        ToolDef("timer:5", "Timer 5m", R.drawable.ic_timer, "Timers"),
        ToolDef("timer:10", "Timer 10m", R.drawable.ic_timer, "Timers"),
        ToolDef("timer:25", "Focus 25m", R.drawable.ic_timer, "Timers"),
        ToolDef("timer:custom", "Timer…", R.drawable.ic_timer, "Timers"),
        ToolDef("stopwatch", "Stopwatch", R.drawable.ic_stopwatch, "Timers"),
        ToolDef("play", "Play / pause", R.drawable.ic_play, "Media"),
        ToolDef("next", "Next track", R.drawable.ic_next, "Media"),
        ToolDef("prev", "Previous", R.drawable.ic_prev, "Media"),
        ToolDef("volume", "Volume", R.drawable.ic_volume, "Sound"),
        ToolDef("ringer", "Ring / vibrate", R.drawable.ic_bell, "Sound"),
        ToolDef("dnd", "Do Not Disturb", R.drawable.ic_moon, "Sound", ToolNeed.DND_ACCESS),
        ToolDef("wifi", "Internet", R.drawable.ic_wifi, "Connectivity"),
        ToolDef("bt", "Bluetooth", R.drawable.ic_bluetooth, "Connectivity"),
        ToolDef("nfc", "NFC", R.drawable.ic_nfc, "Connectivity"),
        ToolDef("lock", "Lock screen", R.drawable.ic_lock, "System"),
        ToolDef("power", "Power menu", R.drawable.ic_power, "System"),
        ToolDef("recents", "Recent apps", R.drawable.ic_recents, "System"),
        ToolDef("split", "Split screen", R.drawable.ic_split, "System"),
        ToolDef("shade", "Notifications", R.drawable.ic_bell, "System"),
        ToolDef("qs", "Quick settings", R.drawable.ic_tune, "System"),
    )

    const val DEFAULT = "torch,timer:1,timer:5,stopwatch,screenshot,note,inbox,otp,play,wifi,volume,dnd"

    fun def(id: String): ToolDef? = builtIn.firstOrNull { it.id == id }
    fun isApp(id: String) = id.startsWith(APP_PREFIX)
    fun appPackage(id: String) = id.removePrefix(APP_PREFIX)
    fun isKnown(id: String) = def(id) != null || (isApp(id) && appPackage(id).isNotBlank())
}

/** The user's tool arrangement, stored as a comma-separated list of ids. Pure Kotlin so it can be unit-tested. */
object ToolLayout {
    fun parse(raw: String?): List<String> =
        (raw ?: ToolCatalog.DEFAULT).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun join(ids: List<String>): String = ids.distinct().joinToString(",")

    fun pages(ids: List<String>, perPage: Int): List<List<String>> =
        if (ids.isEmpty()) listOf(emptyList()) else ids.chunked(perPage.coerceIn(3, 8))

    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices) return ids
        val m = ids.toMutableList(); val x = m.removeAt(from); m.add(to, x); return m
    }
}
