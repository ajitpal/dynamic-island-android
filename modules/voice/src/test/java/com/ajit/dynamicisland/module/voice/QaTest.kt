package com.ajit.dynamicisland.module.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaTest {
    private val lines = (0 until 120).map { i ->
        Line(i * 10_000L, when (i) {
            47 -> "The vendor contract renewal is due on the fifteenth."
            48 -> "Legal wants two more weeks to review the contract."
            90 -> "Budget for marketing stays at forty lakh."
            else -> "General discussion point number $i about the roadmap."
        })
    }

    @Test fun keywordsDropStopWords() {
        assertEquals(setOf("vendor", "contract", "due"), Qa.keywords("When is the vendor contract due?"))
    }

    @Test fun picksRelevantWindows() {
        val ex = Qa.excerpts(lines, "What did legal say about the vendor contract?", 900)
        assertTrue(ex.contains("Legal wants two more weeks"))
        assertTrue(ex.contains("[7:50] The vendor contract renewal"))
        assertFalse(ex.contains("forty lakh"))
        assertTrue(ex.length <= 900)
    }

    @Test fun wholeTranscriptWhenItFits() {
        val short = lines.take(5)
        assertEquals(5, Qa.excerpts(short, "anything", 10_000).lines().size)
    }

    @Test fun parsesStamps() {
        val s = Qa.stamps("It was at [7:50] and again [1:02:03].")
        assertEquals(listOf(470_000L, 3_723_000L), s.map { it.third })
        assertEquals("[7:50]", "It was at [7:50] and again [1:02:03].".substring(s[0].first, s[0].second))
    }
}
