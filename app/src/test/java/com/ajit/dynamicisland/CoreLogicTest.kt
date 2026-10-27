package com.ajit.dynamicisland

import com.ajit.dynamicisland.core.PeekDedupe
import com.ajit.dynamicisland.core.inbox.Otp
import com.ajit.dynamicisland.core.tools.ToolCatalog
import com.ajit.dynamicisland.core.tools.ToolLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    // ---- OTP detection (real-world message shapes) ----
    @Test fun otpKeyThenCode() {
        assertEquals("482913", Otp.extract("482913 is your OTP for login. Do not share it with anyone."))
        assertEquals("4821", Otp.extract("Your verification code is 4821"))
        assertEquals("829114", Otp.extract("Use code 829-114 to sign in"))
        assertEquals("123456", Otp.extract("OTP: 123456. Valid for 10 mins - HDFC Bank"))
        assertEquals("907112", Otp.extract("Dear customer, 907112 is the OTP for your transaction of Rs 2,499"))
    }

    @Test fun otpIgnoresNonCodes() {
        assertNull(Otp.extract("Your order #4567 has been shipped"))
        assertNull(Otp.extract("Meeting at 10:30 tomorrow"))
        assertNull(Otp.extract("Paid ₹349 to Blinkit"))
        assertNull(Otp.extract(null))
    }

    // ---- Tool layout ----
    @Test fun layoutParsesDefaultAndDedupes() {
        assertEquals(ToolLayout.parse(ToolCatalog.DEFAULT), ToolLayout.parse(null))
        assertEquals(listOf("torch", "note"), ToolLayout.parse(" torch, note ,torch,, "))
    }

    @Test fun layoutPagesAndMove() {
        val ids = (1..10).map { "t$it" }
        assertEquals(listOf(6, 4), ToolLayout.pages(ids, 6).map { it.size })
        assertEquals(listOf(8, 2), ToolLayout.pages(ids, 20).map { it.size }) // clamped to 8
        assertEquals(listOf("b", "a", "c"), ToolLayout.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b"), ToolLayout.move(listOf("a", "b"), 0, 5)) // out of range: unchanged
    }

    @Test fun catalogKnowsDefaults() {
        ToolLayout.parse(null).forEach { assertTrue("unknown default tool $it", ToolCatalog.isKnown(it)) }
        assertTrue(ToolCatalog.isKnown("app:com.whatsapp"))
    }

    // ---- Peek de-duplication ----
    @Test fun dedupeSignatureIgnoresCaseAndSpaces() {
        assertEquals(
            PeekDedupe.signature("com.whatsapp", " Priya ", "Dinner at 7?"),
            PeekDedupe.signature("com.whatsapp", "priya", "dinner at 7? "),
        )
    }
}
