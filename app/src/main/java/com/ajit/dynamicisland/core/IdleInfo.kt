package com.ajit.dynamicisland.core

/**
 * The info pill (2.4, GitHub issue #1): when nothing is running, the island can stay as a small pill
 * with one item left of the camera and one right of it, instead of shrinking to the camera hole.
 * What each item says is decided here, in pure Kotlin, so it is unit-tested.
 */
object IdleInfo {
    enum class Style(val label: String) { HIDDEN("Hidden"), CAMERA("Camera Only"), INFO("Info Pill") }

    enum class Item(val label: String) {
        NONE("Nothing"), TIME("Time"), BATTERY("Battery"), DATE("Date"), ALARM("Next Alarm"), UNREAD("Unread Notifications"), NETWORK("Network")
    }

    enum class Net { WIFI, MOBILE, OFFLINE }

    /** Everything the pill can show, read by the overlay once a second. */
    data class Now(
        val hour: Int, val minute: Int, val is24h: Boolean,
        val weekday: String, val day: Int,
        val battery: Int, val charging: Boolean,
        /** Next alarm as hour/minute, or null. */
        val alarm: Pair<Int, Int>?,
        val unread: Int, val net: Net,
    )

    /** What one side shows: a short text, an optional icon, and a colour hint. */
    data class Cell(val text: String, val icon: Icon?, val warn: Boolean = false, val good: Boolean = false, val spoken: String = text)

    enum class Icon { BATTERY, BOLT, ALARM, BELL, WIFI, MOBILE, OFFLINE }

    fun clock(h: Int, m: Int, is24h: Boolean): String =
        if (is24h) "%d:%02d".format(h, m) else "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, m)

    fun cell(item: Item, n: Now): Cell? = when (item) {
        Item.NONE -> null
        Item.TIME -> Cell(clock(n.hour, n.minute, n.is24h), null)
        Item.DATE -> Cell("${n.weekday} ${n.day}", null)
        Item.BATTERY -> if (n.battery !in 0..100) null else Cell("${n.battery}%", if (n.charging) Icon.BOLT else Icon.BATTERY,
            warn = n.battery <= 15 && !n.charging, good = n.charging,
            spoken = "Battery ${n.battery} percent" + if (n.charging) ", charging" else "")
        Item.ALARM -> n.alarm?.let { (h, m) -> Cell(clock(h, m, n.is24h), Icon.ALARM, spoken = "Alarm at ${clock(h, m, n.is24h)}") }
        Item.UNREAD -> if (n.unread <= 0) null else Cell(if (n.unread > 99) "99+" else n.unread.toString(), Icon.BELL, spoken = "${n.unread} unread")
        Item.NETWORK -> when (n.net) {
            Net.WIFI -> Cell("", Icon.WIFI, spoken = "Wi-Fi")
            Net.MOBILE -> Cell("", Icon.MOBILE, spoken = "Mobile data")
            Net.OFFLINE -> Cell("Offline", Icon.OFFLINE, warn = true)
        }
    }

    /** TalkBack text for the whole pill. */
    fun spoken(left: Cell?, right: Cell?) = listOfNotNull(left?.spoken, right?.spoken).joinToString(", ").ifBlank { "Dynamic Island" }
}
