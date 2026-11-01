package com.ajit.dynamicisland.core

import com.ajit.dynamicisland.core.IdleInfo.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleInfoTest {
    private val now = IdleInfo.Now(19, 5, is24h = false, weekday = "Sun", day = 1, battery = 84, charging = false,
        alarm = 6 to 30, unread = 3, net = IdleInfo.Net.WIFI)

    @Test fun time12And24() {
        assertEquals("7:05", IdleInfo.cell(Item.TIME, now)!!.text)
        assertEquals("19:05", IdleInfo.cell(Item.TIME, now.copy(is24h = true))!!.text)
        assertEquals("12:00", IdleInfo.clock(0, 0, false)); assertEquals("0:00", IdleInfo.clock(0, 0, true))
    }

    @Test fun battery() {
        val b = IdleInfo.cell(Item.BATTERY, now)!!
        assertEquals("84%", b.text); assertEquals(IdleInfo.Icon.BATTERY, b.icon); assertFalse(b.warn)
        assertTrue(IdleInfo.cell(Item.BATTERY, now.copy(battery = 12))!!.warn)
        val c = IdleInfo.cell(Item.BATTERY, now.copy(battery = 12, charging = true))!!
        assertFalse(c.warn); assertTrue(c.good); assertEquals(IdleInfo.Icon.BOLT, c.icon)
        assertEquals("Battery 12 percent, charging", c.spoken)
        assertNull(IdleInfo.cell(Item.BATTERY, now.copy(battery = -1)))
    }

    @Test fun otherItems() {
        assertEquals("Sun 1", IdleInfo.cell(Item.DATE, now)!!.text)
        assertEquals("6:30", IdleInfo.cell(Item.ALARM, now)!!.text)
        assertNull(IdleInfo.cell(Item.ALARM, now.copy(alarm = null)))
        assertEquals("3", IdleInfo.cell(Item.UNREAD, now)!!.text)
        assertNull(IdleInfo.cell(Item.UNREAD, now.copy(unread = 0)))
        assertEquals("99+", IdleInfo.cell(Item.UNREAD, now.copy(unread = 250))!!.text)
        assertEquals("Offline", IdleInfo.cell(Item.NETWORK, now.copy(net = IdleInfo.Net.OFFLINE))!!.text)
        assertNull(IdleInfo.cell(Item.NONE, now))
    }

    @Test fun spokenForTalkBack() {
        assertEquals("7:05, Battery 84 percent", IdleInfo.spoken(IdleInfo.cell(Item.TIME, now), IdleInfo.cell(Item.BATTERY, now)))
        assertEquals("Dynamic Island", IdleInfo.spoken(null, null))
    }
}
