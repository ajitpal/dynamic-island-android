package com.ajit.dynamicisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandStackTest {
    private data class A(val id: String, val call: Boolean = false)
    private fun arrange(list: List<A>, pref: String?) = IslandStack.arrange(list, pref) { it.id }

    @Test fun emptyShowsNothing() {
        val s = arrange(emptyList(), null)
        assertNull(s.primary); assertNull(s.bubble); assertEquals(0, s.hidden)
    }

    @Test fun oneActivityHasNoCircle() {
        val s = arrange(listOf(A("a")), null)
        assertEquals("a", s.primary?.id); assertNull(s.bubble); assertEquals(0, s.hidden)
    }

    @Test fun twoActivitiesNoBadge() {
        val s = arrange(listOf(A("a"), A("b")), null)
        assertEquals("a", s.primary?.id); assertEquals("b", s.bubble?.id); assertNull(IslandStack.badge(s.hidden))
    }

    @Test fun threeActivitiesShowPlusOne() {
        val s = arrange(listOf(A("a"), A("b"), A("c")), null)
        assertEquals("a", s.primary?.id); assertEquals("b", s.bubble?.id)
        assertEquals("+1", IslandStack.badge(s.hidden))
    }

    @Test fun tappingTheCircleWalksThroughEveryActivityAndWraps() {
        val list = listOf(A("a"), A("b"), A("c"))
        var pref: String? = null
        val seen = mutableListOf<String>()
        repeat(4) {
            val s = arrange(list, pref)
            seen += s.primary!!.id
            pref = s.bubble!!.id // tap the circle
        }
        assertEquals(listOf("a", "b", "c", "a"), seen)
    }

    @Test fun circleShowsWhatTheTapWillOpen() {
        val list = listOf(A("a"), A("b"), A("c"))
        val s = arrange(list, "c")
        assertEquals("c", s.primary?.id); assertEquals("a", s.bubble?.id)
    }

    @Test fun choiceIsDroppedWhenItEnds() {
        val kept = IslandStack.keepPreferred("x", listOf(A("a")), setOf("a", "x"), { it.id }, { it.call })
        assertNull(kept)
    }

    @Test fun newCallTakesTheIslandBack() {
        val list = listOf(A("call", call = true), A("a"), A("b"))
        assertNull(IslandStack.keepPreferred("b", list, setOf("a", "b"), { it.id }, { it.call }))
    }

    @Test fun ongoingCallDoesNotUndoAChoice() {
        val list = listOf(A("call", call = true), A("a"), A("b"))
        assertEquals("b", IslandStack.keepPreferred("b", list, setOf("call", "a", "b"), { it.id }, { it.call }))
    }

    @Test fun talkBackDescribesTheBadge() {
        assertEquals("Switch to Rec", IslandStack.bubbleDescription("Rec", 0))
        assertEquals("Switch to Rec. 2 more activities. Touch and hold to see all", IslandStack.bubbleDescription("Rec", 2))
    }
}
