package com.ajit.dynamicisland.module.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuadTest {
    /** A dark table with a bright, slightly tilted page. */
    private fun page(w: Int, h: Int, inside: (Int, Int) -> Boolean) = IntArray(w * h) { i -> if (inside(i % w, i / w)) 230 else 40 + (i * 7) % 20 }

    @Test fun findsAnUprightPage() {
        val w = 200; val h = 300
        val q = Quad.detect(page(w, h) { x, y -> x in 40..160 && y in 50..250 }, w, h)
        assertTrue(q.found)
        assertEquals(40.5f / w, q.tl.x, 0.01f); assertEquals(50.5f / h, q.tl.y, 0.01f)
        assertEquals(160.5f / w, q.br.x, 0.01f); assertEquals(250.5f / h, q.br.y, 0.01f)
    }

    @Test fun findsATiltedPage() {
        val w = 300; val h = 300; val a = Math.toRadians(10.0); val c = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
        // A 160×220 page turned 10° around the middle.
        val q = Quad.detect(page(w, h) { x, y ->
            val dx = x - 150.0; val dy = y - 150.0
            abs(dx * c + dy * sn) < 80 && abs(-dx * sn + dy * c) < 110
        }, w, h)
        assertTrue(q.found)
        fun corner(u: Double, v: Double) = Pair(((150 + u * c - v * sn) / w).toFloat(), ((150 + u * sn + v * c) / h).toFloat())
        val (tlx, tly) = corner(-80.0, -110.0); val (brx, bry) = corner(80.0, 110.0)
        assertEquals(tlx, q.tl.x, 0.02f); assertEquals(tly, q.tl.y, 0.02f)
        assertEquals(brx, q.br.x, 0.02f); assertEquals(bry, q.br.y, 0.02f)
    }

    @Test fun noPageGivesAnInsetFrame() {
        val w = 100; val h = 100
        assertFalse(Quad.detect(IntArray(w * h) { 128 }, w, h).found)
        assertFalse(Quad.detect(page(w, h) { x, y -> x < 5 && y < 5 }, w, h).found)   // too small
    }

    @Test fun outputKeepsThePagesShape() {
        val (ow, oh) = Quad.inset(0.1f).outputSize(1000, 2000)
        assertEquals(800, ow); assertEquals(1600, oh)
        val (bw, bh) = Quad.inset(0f).outputSize(6000, 4000, maxSide = 2000)
        assertEquals(2000, bw); assertEquals(1333, bh)
    }

    @Test fun otsuSplitsTwoGroups() {
        val t = Quad.otsu(IntArray(100) { if (it < 50) 30 else 220 })
        assertTrue(t in 30 until 220)
    }
}
