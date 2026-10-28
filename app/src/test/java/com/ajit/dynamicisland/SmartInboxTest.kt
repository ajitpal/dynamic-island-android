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

    @Test fun islandDigestIsShort() {
        val t = "## Needs attention\n- Priya asked if you can join at 7\n- HDFC payment of Rs 2,499 done\n## Everything else\n- WhatsApp: 4 group messages\n- Swiggy: order delivered\n- Gmail: 3 newsletters\n- Uber: trip receipt\n- Extra line"
        val out = SmartInbox.forIsland(t)
        assertTrue(out.startsWith("• Priya asked"))
        assertTrue(out.contains("Also:"))
        assertTrue(out.lines().size <= 6)
        assertFalse(out.contains("#"))
    }

    @Test fun groupsByAppAndCapsEachApp() {
        val many = List(10) { e("WhatsApp", "Group", "Message $it") } + e("Gmail", "Bank", "Statement ready")
        val p = SmartInbox.digestPrompt(many)
        assertEquals(4, Regex("\\[WhatsApp\\] Group").findAll(p).count())
        assertTrue(p.contains("[WhatsApp] and 6 more"))
        assertTrue(p.contains("[Gmail] Bank: Statement ready"))
    }

    @Test fun dropsIdenticalLines() {
        val p = SmartInbox.digestPrompt(List(5) { e("Swiggy", "Order", "Out for delivery") })
        assertEquals(1, Regex("Out for delivery").findAll(p).count())
    }

    @Test fun splitsSummaryIntoSections() {
        val s = SmartInbox.sections("## Needs attention\n- Priya asked about **7 pm**\n## Everything else\n- Gmail: 3 newsletters\n")
        assertEquals(listOf("Needs Attention", "Everything Else"), s.map { it.first })
        assertEquals("Priya asked about 7 pm", s[0].second.single())
        assertEquals(listOf("Summary" to listOf("Just one line")), SmartInbox.sections("Just one line"))
    }

    /** Real output from Qwen3 0.6B (desktop run of the same model file): headings inside bullets, double bullets. */
    @Test fun toleratesSmallModelFormatting() {
        val t = "- **## Needs attention**  \n- **## Everything else**  \n- - [WhatsApp] Priya: Dinner at 7? I booked the table  \n- - [HDFC Bank] Payment: Rs 2,499 paid to Swiggy"
        val s = SmartInbox.sections(t)
        assertEquals("Everything Else", s.single().first)
        assertEquals(listOf("[WhatsApp] Priya: Dinner at 7? I booked the table", "[HDFC Bank] Payment: Rs 2,499 paid to Swiggy"), s.single().second)
        assertFalse(SmartInbox.forIsland(t).contains("##"))
    }
}
