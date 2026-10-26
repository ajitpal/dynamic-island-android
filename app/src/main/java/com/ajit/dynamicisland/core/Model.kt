package com.ajit.dynamicisland.core

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.session.MediaController

/**
 * What kind of live activity is shown in the island. The base priority decides which
 * activity wins the main pill when several are running (the runner-up goes to the bubble).
 */
enum class Kind(val basePriority: Int) {
    CALL(100),        // incoming / ongoing calls (phone, WhatsApp, Telegram...)
    SYSTEM(90),       // short system events: charging, silent mode, bluetooth, timer done
    NAVIGATION(80),   // turn-by-turn directions
    TIMER(70),        // timers, stopwatches, anything with a running chronometer
    MEDIA(60),        // now playing (any app with a MediaSession)
    LIVE(55),         // live scores & Android 16 "Live Updates" (promoted ongoing notifications)
    PROGRESS(50),     // downloads, uploads, food delivery, cab ETA (progress notifications)
    MESSAGE(45),      // incoming chat/SMS/email previews
    TOOL(25),         // island tools: flashlight
    NOTE(20),         // a note the user pinned to the island
    GENERIC(10),      // any other notification (opt-in)
}

enum class ActionStyle { NORMAL, POSITIVE, NEGATIVE }

/** A button shown in the expanded island. */
class IslandAction(
    val title: String,
    val intent: PendingIntent? = null,
    val style: ActionStyle = ActionStyle.NORMAL,
    val onClick: (() -> Unit)? = null,
    /** When set, the action opens an inline text field (quick reply, note) and receives the text. */
    val onInput: ((String) -> Unit)? = null,
    val inputHint: String? = null,
)

data class IslandActivity(
    val id: String,
    val kind: Kind,
    val packageName: String,
    val title: CharSequence,
    val text: CharSequence? = null,
    val icon: Drawable? = null,
    val image: Bitmap? = null,
    val accent: Int = Colors.BLUE,
    /** Tint [icon] with [accent] (for our own monochrome vector icons). */
    val tintIcon: Boolean = false,
    val compactText: CharSequence? = null,
    /** 0..1, or -1 when there is no progress. */
    val progress: Float = -1f,
    val indeterminate: Boolean = false,
    /** Wall-clock millis. Count-up: start time. Count-down: end time. 0 = none. */
    val chronoBase: Long = 0L,
    val countDown: Boolean = false,
    val incoming: Boolean = false,
    val isPlaying: Boolean = false,
    val media: MediaController? = null,
    val contentIntent: PendingIntent? = null,
    val onTap: (() -> Unit)? = null,
    val actions: List<IslandAction> = emptyList(),
    val appLabel: String? = null,
    /** Pop the island open automatically when this activity appears / changes. */
    val peek: Boolean = false,
    /** How long a peek stays open. 0 = until the activity changes (incoming calls). */
    val peekMs: Long = 4000L,
    /** Wall-clock millis at which the activity removes itself. 0 = never. */
    val expiresAt: Long = 0L,
    val priority: Int = kind.basePriority,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Changes whenever the visible content changes; used to de-duplicate peeks. */
    val contentHash: Int = 0,
)

object Colors {
    const val GREEN = 0xFF34C759.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val PURPLE = 0xFFBF5AF2.toInt()
    const val PINK = 0xFFFF375F.toInt()
    const val GRAY = 0xFF8E8E93.toInt()
    const val SURFACE = 0xFF2C2C2E.toInt()
    const val TEXT_SECONDARY = 0xFFAEAEB2.toInt()

    /** A bright colour picked from artwork, readable on a black island. */
    fun vivid(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null
        return try {
            val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            val px = Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.45f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            Color.HSVToColor(hsv)
        } catch (e: Exception) {
            null
        }
    }
}

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun chronoText(a: IslandActivity): String {
    val now = System.currentTimeMillis()
    return formatClock(if (a.countDown) a.chronoBase - now else now - a.chronoBase)
}
