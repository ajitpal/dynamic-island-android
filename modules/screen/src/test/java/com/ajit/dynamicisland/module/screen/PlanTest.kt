package com.ajit.dynamicisland.module.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    @Test fun keepsAspectAndFitsQuality() {
        val (w, h) = Plan.videoSize(1080, 2400, 720)
        assertEquals(720, w); assertEquals(1600, h)
        assertEquals(0, w % 16); assertEquals(0, h % 16)
    }

    @Test fun neverScalesUp() {
        assertEquals(1072 to 2400, Plan.videoSize(1080, 2400, Int.MAX_VALUE))
        assertEquals(1072 to 2400, Plan.videoSize(1080, 2400, 1080))
    }

    @Test fun worksInLandscape() {
        val (w, h) = Plan.videoSize(2400, 1080, 720)
        assertEquals(1600, w); assertEquals(720, h)
    }

    @Test fun bitrateStaysInRange() {
        assertEquals(2_000_000, Plan.bitrate(320, 480, 30))
        assertEquals(20_000_000, Plan.bitrate(1440, 3200, 60))
        val mid = Plan.bitrate(1072, 2400, 30)
        assertTrue(mid in 7_000_000..8_000_000)
    }

    @Test fun fileNamesAreSafeAndSortable() {
        assertEquals("Screen Recording 2026-10-31 at 09.05.03.mp4", Plan.fileName(2026, 10, 31, 9, 5, 3))
    }

    @Test fun clock() {
        assertEquals("0:00", Plan.clock(0)); assertEquals("1:05", Plan.clock(65_400)); assertEquals("1:00:01", Plan.clock(3_601_000))
    }

    @Test fun pausesAreCutOut() {
        val c = PauseClock()
        assertNull(c.map(500))            // before start
        c.start(1_000)
        assertEquals(0L, c.map(1_000))
        assertEquals(4_000L, c.map(5_000))
        c.pause(6_000)
        assertNull(c.map(7_000))          // while paused: dropped
        c.resume(10_000)                  // 4 ms paused
        assertEquals(6_000L, c.map(11_000))
        assertEquals(6_000L, c.elapsedUs(11_000))
    }

    @Test fun elapsedFreezesWhilePaused() {
        val c = PauseClock(); c.start(0); c.pause(3_000_000)
        assertEquals(3_000_000L, c.elapsedUs(9_000_000))
        assertTrue(c.paused)
    }

    @Test fun mixClipsInsteadOfWrapping() {
        val out = Mix.add(shortArrayOf(30_000, -30_000, 100), shortArrayOf(10_000, -10_000, 23), 3)
        assertEquals(Short.MAX_VALUE, out[0]); assertEquals(Short.MIN_VALUE, out[1]); assertEquals(123.toShort(), out[2])
    }

    @Test fun soundOptionsNeedTheMicPermission() {
        assertTrue(Audio.PHONE.needsPermission && Audio.MIC.needsPermission && Audio.BOTH.needsPermission && !Audio.NONE.needsPermission)
    }
}
