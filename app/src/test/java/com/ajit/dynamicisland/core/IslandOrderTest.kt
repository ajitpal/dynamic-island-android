package com.ajit.dynamicisland.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Issue #2: an app that re-posts every second must not swap places with the activity beside it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IslandOrderTest {
    @Before fun clear() = IslandRepository.removeWhere { true }

    private fun act(id: String, t: Long, hash: Int = 1, peek: Boolean = false) =
        IslandActivity(id = id, kind = Kind.TIMER, packageName = "p", title = id, updatedAt = t, contentHash = hash, peek = peek)

    private fun order() = IslandRepository.activities.value.values
        .sortedWith(compareByDescending<IslandActivity> { it.priority }.thenByDescending { it.updatedAt }.thenBy { it.id }).map { it.id }

    @Test fun repostsKeepTheirPlace() {
        IslandRepository.upsert(act("rec", 1000))
        IslandRepository.upsert(act("timer", 2000))
        assertEquals(listOf("timer", "rec"), order())
        repeat(5) { i -> IslandRepository.upsert(act("rec", 3000L + i, hash = 1)) }      // recorder re-posts every second
        repeat(5) { i -> IslandRepository.upsert(act("rec", 4000L + i, hash = 10 + i)) } // even with changing text
        assertEquals(listOf("timer", "rec"), order())
    }

    @Test fun somethingNewThatPopsComesForward() {
        IslandRepository.upsert(act("a", 1000)); IslandRepository.upsert(act("b", 2000))
        IslandRepository.upsert(act("a", 3000, hash = 2, peek = true))
        assertEquals(listOf("a", "b"), order())
    }
}
