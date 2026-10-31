package com.ajit.dynamicisland.module.scanner

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the page in a photo, without OpenCV or ML Kit: the paper is usually the largest bright area,
 * so threshold the brightness (Otsu), keep the largest connected bright region, and take its four
 * extreme corners. Good enough for a starting point; the person drags the corners to fix it.
 * Points are in 0..1 (fractions of width and height). Pure Kotlin, unit-tested.
 */
data class Quad(val tl: P, val tr: P, val br: P, val bl: P, val found: Boolean) {
    data class P(val x: Float, val y: Float)

    val points get() = listOf(tl, tr, br, bl)

    /** Pixel size of the straightened page for an image of [w]×[h]. */
    fun outputSize(w: Int, h: Int, maxSide: Int = 2000): Pair<Int, Int> {
        fun d(a: P, b: P) = hypot(((a.x - b.x) * w).toDouble(), ((a.y - b.y) * h).toDouble())
        var ow = max(d(tl, tr), d(bl, br)); var oh = max(d(tl, bl), d(tr, br))
        val s = minOf(1.0, maxSide / max(ow, oh))
        ow *= s; oh *= s
        return ow.roundToInt().coerceAtLeast(16) to oh.roundToInt().coerceAtLeast(16)
    }

    companion object {
        /** A rectangle inset by [m] on every side: the fallback when no page is found. */
        fun inset(m: Float = 0.08f) = Quad(P(m, m), P(1 - m, m), P(1 - m, 1 - m), P(m, 1 - m), found = false)

        /** Brightness threshold that best splits the picture into two groups. */
        fun otsu(lum: IntArray): Int {
            val hist = IntArray(256); lum.forEach { hist[it.coerceIn(0, 255)]++ }
            val total = lum.size.toDouble()
            var sum = 0.0; for (i in 0..255) sum += i * hist[i].toDouble()
            var sumB = 0.0; var wB = 0.0; var best = 0.0; var t = 127
            for (i in 0..255) {
                wB += hist[i]; if (wB == 0.0) continue
                val wF = total - wB; if (wF == 0.0) break
                sumB += i * hist[i].toDouble()
                val mB = sumB / wB; val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                if (between > best) { best = between; t = i }
            }
            return t
        }

        /** [lum]: brightness 0..255, row by row, [w]×[h] (a small copy of the photo, ~300 px). */
        fun detect(lum: IntArray, w: Int, h: Int): Quad {
            if (w < 8 || h < 8 || lum.size != w * h) return inset()
            val t = otsu(lum)
            val bright = BooleanArray(lum.size) { lum[it] > t }
            // Largest 4-connected bright region (iterative flood fill).
            val label = IntArray(lum.size); var bestLabel = 0; var bestSize = 0; var next = 1
            val queue = IntArray(lum.size)
            for (start in lum.indices) {
                if (!bright[start] || label[start] != 0) continue
                var head = 0; var tail = 0; queue[tail++] = start; label[start] = next; var size = 0
                while (head < tail) {
                    val i = queue[head++]; size++
                    val x = i % w; val y = i / w
                    if (x > 0) { val j = i - 1; if (bright[j] && label[j] == 0) { label[j] = next; queue[tail++] = j } }
                    if (x < w - 1) { val j = i + 1; if (bright[j] && label[j] == 0) { label[j] = next; queue[tail++] = j } }
                    if (y > 0) { val j = i - w; if (bright[j] && label[j] == 0) { label[j] = next; queue[tail++] = j } }
                    if (y < h - 1) { val j = i + w; if (bright[j] && label[j] == 0) { label[j] = next; queue[tail++] = j } }
                }
                if (size > bestSize) { bestSize = size; bestLabel = next }
                next++
            }
            // Too small to be a page, or the whole frame (no contrast between page and background).
            if (bestSize < lum.size * 0.12 || bestSize > lum.size * 0.97) return inset()
            var tl = 0; var tr = 0; var br = 0; var bl = 0
            var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
            for (i in lum.indices) {
                if (label[i] != bestLabel) continue
                val x = i % w; val y = i / w; val s = x + y; val d = x - y
                if (s < minS) { minS = s; tl = i }
                if (s > maxS) { maxS = s; br = i }
                if (d > maxD) { maxD = d; tr = i }
                if (d < minD) { minD = d; bl = i }
            }
            fun p(i: Int) = P((i % w + 0.5f) / w, (i / w + 0.5f) / h)
            return Quad(p(tl), p(tr), p(br), p(bl), found = true)
        }
    }
}
