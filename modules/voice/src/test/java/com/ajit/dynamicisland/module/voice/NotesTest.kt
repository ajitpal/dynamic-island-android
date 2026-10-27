package com.ajit.dynamicisland.module.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val lines = listOf(
        Line(0, "Okay."),
        Line(4_000, "Thanks everyone for joining. Let's start with the Q3 numbers."),
        Line(20_000, "Revenue is up eight percent."),
        Line(41_000, "Priya will send the updated forecast by Friday."),
        Line(65_000, "We need to fix the onboarding drop-off before the launch."),
        Line(90_000, "Yeah."),
        Line(95_000, "I'll follow up with legal on the vendor contract next week."),
    )

    @Test fun findsCommitments() {
        val items = Notes.actionItems(lines).map { it.text }
        assertTrue(items.contains("Priya will send the updated forecast by Friday."))
        assertTrue(items.contains("We need to fix the onboarding drop-off before the launch."))
        assertTrue(items.contains("I'll follow up with legal on the vendor contract next week."))
        assertTrue(items.none { it.startsWith("Revenue") || it == "Yeah." || it == "Okay." })
    }

    @Test fun keepsTimestamps() {
        assertEquals(41_000L, Notes.actionItems(lines).first { it.text.startsWith("Priya") }.startMs)
    }

    @Test fun clockFormats() {
        assertEquals("0:05", Notes.clock(5_400))
        assertEquals("12:03", Notes.clock(723_000))
        assertEquals("1:02:03", Notes.clock(3_723_000))
    }

    @Test fun exportHasSections() {
        val txt = Notes.toText("Weekly sync", "Tue 27 Oct", 120_000, lines)
        assertTrue(txt.contains("Possible action items") && txt.contains("[0:41] Priya will send"))
    }
}
