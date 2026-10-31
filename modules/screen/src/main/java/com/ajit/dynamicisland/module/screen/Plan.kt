package com.ajit.dynamicisland.module.screen

import kotlin.math.roundToInt

/** What to record alongside the picture. Everything except [NONE] needs the microphone permission (Android rule, even for phone sound). */
enum class Audio(val label: String, val short: String, val detail: String, val usesMic: Boolean, val usesPlayback: Boolean) {
    NONE("No Sound", "Off", "Video only", false, false),
    MIC("Microphone", "Microphone", "Your voice and the room", true, false),
    PHONE("Phone Sound", "Phone", "What apps play, where they allow it", false, true),
    BOTH("Phone Sound + Microphone", "Phone + Mic", "Mixed together", true, true);

    val needsPermission get() = this != NONE
}

/** Upper limit for the shorter side of the video. */
enum class Quality(val label: String, val maxShortSide: Int) {
    HD720("720p", 720), HD1080("1080p", 1080), FULL("Full Resolution", Int.MAX_VALUE)
}

/** Pure sizing and naming rules, unit-tested without a phone. */
object Plan {
    const val FOLDER = "Movies/Dynamic Island"
    const val SAMPLE_RATE = 44_100

    /**
     * Video size for a screen of [w]×[h] pixels: scaled down so the shorter side fits [maxShort],
     * aspect ratio kept, each side a multiple of 16 (what hardware H.264 encoders handle everywhere).
     */
    fun videoSize(w: Int, h: Int, maxShort: Int): Pair<Int, Int> {
        require(w > 0 && h > 0)
        val short = minOf(w, h)
        val scale = if (short > maxShort) maxShort.toDouble() / short else 1.0
        fun fit(v: Int) = ((v * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
        return fit(w) to fit(h)
    }

    /** About 0.1 bits per pixel per frame: crisp text and UI, files of roughly 1–3 MB per 10 seconds at 1080p30. */
    fun bitrate(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps / 10).toInt().coerceIn(2_000_000, 20_000_000)

    /** "Screen Recording 2026-10-31 at 14.05.32.mp4" (no characters some file systems reject). */
    fun fileName(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int) =
        "Screen Recording %04d-%02d-%02d at %02d.%02d.%02d.mp4".format(year, month, day, hour, min, sec)

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * Turns capture timestamps (µs, monotonic clock) into file timestamps that start at 0 and skip
 * paused stretches. Shared by the video and audio threads.
 */
class PauseClock {
    private var startUs = -1L
    private var pausedAtUs = -1L
    private var pausedTotalUs = 0L
    val paused get() = pausedAtUs >= 0

    @Synchronized fun start(nowUs: Long) { startUs = nowUs; pausedAtUs = -1; pausedTotalUs = 0 }
    @Synchronized fun pause(nowUs: Long) { if (startUs >= 0 && pausedAtUs < 0) pausedAtUs = nowUs }
    @Synchronized fun resume(nowUs: Long) { if (pausedAtUs >= 0) { pausedTotalUs += (nowUs - pausedAtUs).coerceAtLeast(0); pausedAtUs = -1 } }

    /** Time in the file for a sample captured at [ptsUs], or null to drop it (before the start, or while paused). */
    @Synchronized fun map(ptsUs: Long): Long? {
        if (startUs < 0 || ptsUs < startUs) return null
        if (pausedAtUs >= 0 && ptsUs >= pausedAtUs) return null
        val t = ptsUs - startUs - pausedTotalUs
        return if (t < 0) null else t
    }

    /** Recorded length so far, without pauses. */
    @Synchronized fun elapsedUs(nowUs: Long): Long {
        if (startUs < 0) return 0
        val end = if (pausedAtUs >= 0) pausedAtUs else nowUs
        return (end - startUs - pausedTotalUs).coerceAtLeast(0)
    }
}

object Mix {
    /** Adds two 16-bit PCM streams sample by sample, clipping instead of wrapping around. */
    fun add(a: ShortArray, b: ShortArray, n: Int, out: ShortArray = ShortArray(n)): ShortArray {
        for (i in 0 until n) out[i] = (a[i] + b[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        return out
    }
}
