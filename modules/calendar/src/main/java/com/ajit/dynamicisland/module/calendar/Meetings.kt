package com.ajit.dynamicisland.module.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

data class Meeting(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val location: String?,
    val joinUrl: String?,
    val calendar: String?,
) {
    val provider: String? get() = joinUrl?.let { MeetingLinks.provider(it) }
    val eventUri: String get() = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId).toString()
}

/** Pure helpers (unit-tested). */
object MeetingLinks {
    private val LINK = Regex(
        "https://(meet\\.google\\.com/[a-z]{3,4}-[a-z]{4}-[a-z]{3,4}|[\\w.-]*zoom\\.us/(j|my|w)/[^\\s<>\"]+|teams\\.microsoft\\.com/l/meetup-join/[^\\s<>\"]+|teams\\.live\\.com/meet/[^\\s<>\"]+|[\\w.-]*webex\\.com/[^\\s<>\"]+)",
        RegexOption.IGNORE_CASE,
    )

    fun find(vararg texts: String?): String? = texts.firstNotNullOfOrNull { t -> t?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', ']', '>') } }

    fun provider(url: String): String = when {
        "meet.google.com" in url -> "Google Meet"
        "zoom.us" in url -> "Zoom"
        "teams." in url -> "Microsoft Teams"
        "webex.com" in url -> "Webex"
        else -> "Online meeting"
    }
}

object Calendars {
    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Meetings overlapping [from, from + windowMs], earliest first. Skips all-day and declined events. */
    fun upcoming(ctx: Context, from: Long = System.currentTimeMillis(), windowMs: Long = 24 * 3600_000L): List<Meeting> {
        if (!hasPermission(ctx)) return emptyList()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = ArrayList<Meeting>()
        try {
            CalendarContract.Instances.query(ctx.contentResolver, proj, from - 6 * 3600_000L, from + windowMs)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) == 1) continue
                    if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val end = c.getLong(3)
                    if (end <= from) continue
                    val loc = c.getString(5); val desc = c.getString(6)
                    out += Meeting(c.getLong(0), c.getString(1)?.ifBlank { null } ?: "(No title)", c.getLong(2), end, loc, MeetingLinks.find(loc, desc), c.getString(8))
                }
            }
        } catch (_: SecurityException) {
        }
        return out.sortedBy { it.begin }
    }
}
