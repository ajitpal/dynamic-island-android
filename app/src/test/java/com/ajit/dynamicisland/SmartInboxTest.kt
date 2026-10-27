package com.ajit.dynamicisland

import com.ajit.dynamicisland.core.ai.SmartInbox
import com.ajit.dynamicisland.core.inbox.InboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInboxTest {
    private fun e(app: String, title: String, text: String) = InboxEntry(1, "k", "p", app, title, text, 0, false, "msg")

    @Test fun masksOtpsBeforeSending() {
        val p = SmartInbox.digestPrompt(listOf(e("Messages", "HDFC", "Your OTP is 482913 for login. Do not share.")))
        assertFalse(p.contains("482913"))
        assertTrue(p.contains("[Messages] HDFC"))
    }

    @Test fun digestRespectsBudget() {
        val many = List(200) { e("WhatsApp", "Priya", "Message number $it about tomorrow's plan and the venue") }
        assertTrue(SmartInbox.digestPrompt(many, 3000).length <= 3100)
    }

    @Test fun parsesReplies() {
        val r = SmartInbox.parseReplies("Here are three:\n1. Sounds good, see you then!\n- \"Can we do 7 instead?\"\n• Running late, 10 min\n4. Extra")
        assertEquals(listOf("Sounds good, see you then!", "Can we do 7 instead?", "Running late, 10 min"), r)
    }
}
