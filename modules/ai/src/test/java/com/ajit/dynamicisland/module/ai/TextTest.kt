package com.ajit.dynamicisland.module.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun stripsThinking() {
        assertEquals("Hello", Text.clean("<think>\nlet me think\n</think>\n\nHello"))
        assertEquals("", Text.clean("<think>still thinking"))   // unfinished block while streaming
        assertEquals("A\n\nB", Text.clean("A\n\nB\n"))
    }

    @Test fun mergesDeltasAndCumulative() {
        var s = ""
        listOf("Hel", "lo", " world").forEach { s = Text.merge(s, it) }
        assertEquals("Hello world", s)
        s = ""
        listOf("Hel", "Hello", "Hello world").forEach { s = Text.merge(s, it) }
        assertEquals("Hello world", s)
    }

    @Test fun budget() {
        assertTrue(Text.maxInputChars(4096, 512) in 10_000..12_000)
        assertTrue(Text.estimateTokens("x".repeat(3200)) >= 1000)
    }
}
