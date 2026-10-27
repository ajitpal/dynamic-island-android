package com.ajit.dynamicisland.module.calendar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ModuleTool
import java.util.Date

/** Next meeting in the island: countdown → pop-up with Join → live progress during the meeting. */
class CalendarModule : IslandModuleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 30_000) }
    }
    private val peeked = HashSet<Long>()
    private var shownId: Long = -1

    override val tools = listOf(ModuleTool("next", "Next meeting", R.drawable.ic_event))

    override fun onIslandConnected() { handler.removeCallbacks(tick); handler.post(tick) }
    override fun onIslandDisconnected() { handler.removeCallbacks(tick) }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

    override fun onToolTapped(toolId: String) {
        if (!Calendars.hasPermission(this)) {
            island.post(IslandCard.Builder(this, "info").title("Calendar access needed").text("Open Island Calendar settings to allow it")
                .icon(R.drawable.ic_event).accent(0xFFFF9F0A.toInt()).style(IslandContract.STYLE_ALERT).expiresIn(4000).build())
            return
        }
        val next = Calendars.upcoming(this, windowMs = 7 * 24 * 3600_000L).firstOrNull()
        if (next == null) {
            island.post(IslandCard.Builder(this, "info").title("No upcoming meetings").text("Nothing in the next 7 days")
                .icon(R.drawable.ic_event).accent(0xFF34C759.toInt()).style(IslandContract.STYLE_ALERT).expiresIn(3500).build())
        } else {
            island.post(cardFor(next, peek = true).expiresIn(12_000).version(next.hashCode() + 7).build())
        }
    }

    /** Show the next meeting once it is within the lead time; keep it during the meeting. */
    fun refresh() {
        if (!island.isConnected) return
        val now = System.currentTimeMillis()
        val lead = Prefs.leadMinutes(this) * 60_000L
        val m = Calendars.upcoming(this, now, 24 * 3600_000L).firstOrNull { it.begin - lead <= now && it.end > now }
        if (m == null) {
            if (shownId != -1L) { island.remove("meeting"); shownId = -1 }
            return
        }
        shownId = m.eventId
        val soon = now >= m.begin - 5 * 60_000L && now < m.begin
        val peek = soon && peeked.add(m.eventId) && Prefs.popUp(this)
        val phase = when { now >= m.begin -> 3; soon -> 2; else -> 1 }
        island.post(cardFor(m, peek).version(phase).build())
    }

    private fun cardFor(m: Meeting, peek: Boolean): IslandCard.Builder {
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeFormat(this)
        val started = now >= m.begin
        val b = IslandCard.Builder(this, "meeting")
            .title(m.title)
            .icon(R.drawable.ic_event)
            .accent(if (started) 0xFF34C759.toInt() else 0xFF0A84FF.toInt())
            .tapUrl(m.eventUri)
        val where = m.provider ?: m.location?.takeIf { it.isNotBlank() }
        if (started) {
            b.style(IslandContract.STYLE_PROGRESS)
                .progress(((now - m.begin).toFloat() / (m.end - m.begin).coerceAtLeast(1)).coerceIn(0f, 1f))
                .text("Now · ends ${time.format(Date(m.end))}" + (where?.let { " · $it" } ?: ""))
                .compact("Now")
        } else {
            b.countdownTo(m.begin)
                .text("${dayPrefix(this, m.begin)}${time.format(Date(m.begin))}–${time.format(Date(m.end))}" + (where?.let { " · $it" } ?: ""))
        }
        m.joinUrl?.let { b.action("join", "Join", ActionStyle.POSITIVE, url = it) }
        b.action("open", "Details", ActionStyle.NORMAL, url = m.eventUri)
        if (peek) b.peek(8000)
        return b
    }

    private fun dayPrefix(ctx: Context, t: Long): String {
        val days = ((t - System.currentTimeMillis()) / (24 * 3600_000L)).toInt()
        return if (days >= 1) android.text.format.DateUtils.formatDateTime(ctx, t, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY) + " " else ""
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("calendar", Context.MODE_PRIVATE)
    fun leadMinutes(c: Context) = sp(c).getInt("lead", 15)
    fun setLeadMinutes(c: Context, v: Int) = sp(c).edit().putInt("lead", v).apply()
    fun popUp(c: Context) = sp(c).getBoolean("popup", true)
    fun setPopUp(c: Context, v: Boolean) = sp(c).edit().putBoolean("popup", v).apply()
}
