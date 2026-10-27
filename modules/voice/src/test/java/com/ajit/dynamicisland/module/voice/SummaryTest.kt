package com.ajit.dynamicisland.module.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTest {
    @Test fun chunksRespectLimit() {
        val lines = (0 until 200).map { Line(it * 5_000L, "Sentence number $it about the quarterly plan and the budget.") }
        val parts = Summary.chunk(lines, 2000)
        assertTrue(parts.size > 3)
        assertTrue(parts.all { it.length <= 2000 })
        assertTrue(parts.first().startsWith("[0:00] Sentence number 0"))
        assertEquals(200, parts.sumOf { p -> p.lines().size })
    }

    @Test fun parsesTypicalAnswer() {
        val answer = """
            ## Title
            - Q3 planning and launch prep
            ## Summary
            - Revenue is up 8%.
            - Onboarding drop-off is the main risk.
            ## Decisions
            - None
            **Action items:**
            1. Priya will send the forecast by Friday.
            * Ajit: follow up with legal
            ## Open questions
            None
        """.trimIndent()
        val (title, sections) = Summary.parse(Template.MEETING, answer)
        assertEquals("Q3 planning and launch prep", title)
        val m = sections.toMap()
        assertEquals(listOf("Revenue is up 8%.", "Onboarding drop-off is the main risk."), m["Summary"])
        assertTrue(m["Decisions"]!!.isEmpty())
        assertEquals(listOf("Priya will send the forecast by Friday.", "Ajit: follow up with legal"), m["Action items"])
        assertTrue(m["Open questions"]!!.isEmpty())
        assertEquals(Template.MEETING.sections, sections.map { it.first })
    }

    @Test fun bulletsBeforeAnyHeadingGoToFirstSection() {
        val (_, s) = Summary.parse(Template.STANDUP, "- Shipped the login fix\n## Blockers\n- Waiting for API keys")
        assertEquals(listOf("Shipped the login fix"), s.toMap()["Done"])
        assertEquals(listOf("Waiting for API keys"), s.toMap()["Blockers"])
    }

    @Test fun groupsForMerge() {
        val g = Summary.groupForMerge(List(10) { "x".repeat(900) }, 3000)
        assertTrue(g.size >= 3 && g.all { it.isNotEmpty() })
        assertEquals(10, g.sumOf { it.size })
    }

    @Test fun promptsMentionHeadings() {
        val p = Summary.partPrompt(Template.MEETING, "[0:01] hi", 0, 1, single = true)
        assertTrue(p.contains("## Title") && p.contains("## Action items") && !p.contains("part 1 of"))
    }
}
