package com.ajit.dynamicisland.core

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single in-process source of truth. Producers (notification listener, media tracker,
 * system monitor, island tools, demos) push activities here; the overlay renders them.
 */
object IslandRepository {
    private val handler = Handler(Looper.getMainLooper())

    private val _activities = MutableStateFlow<Map<String, IslandActivity>>(emptyMap())
    val activities: StateFlow<Map<String, IslandActivity>> = _activities.asStateFlow()

    private val _peeks = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val peeks: SharedFlow<String> = _peeks.asSharedFlow()

    /** Privacy indicators (like the green / orange dots on iOS). */
    val cameraInUse = MutableStateFlow(false)
    val micInUse = MutableStateFlow(false)
    val torchOn = MutableStateFlow(false)

    fun upsert(a: IslandActivity) {
        var changed = false
        _activities.update { cur ->
            val old = cur[a.id]
            changed = old == null || old.contentHash != a.contentHash
            cur + (a.id to a)
        }
        if (a.peek && changed && !recentlyPeeked(a)) _peeks.tryEmit(a.id)
        if (a.expiresAt > 0) {
            val delay = (a.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 30
            handler.postDelayed({ expire(a.id) }, delay)
        }
    }

    private val peekedAt = HashMap<String, Long>()

    /**
     * Same app + same title + same text popped within the last few seconds (e.g. a group
     * summary and its child, or an app re-posting the same message): don't pop the island twice.
     */
    @Synchronized
    private fun recentlyPeeked(a: IslandActivity): Boolean {
        val sig = PeekDedupe.signature(a.packageName, a.title, a.text)
        val now = System.currentTimeMillis()
        peekedAt.entries.removeAll { now - it.value > PeekDedupe.WINDOW_MS }
        val dup = peekedAt.containsKey(sig)
        peekedAt[sig] = now
        return dup
    }

    private fun expire(id: String) {
        val cur = _activities.value[id] ?: return
        if (cur.expiresAt in 1..System.currentTimeMillis()) remove(id)
    }

    fun remove(id: String) = _activities.update { it - id }

    fun removeWhere(predicate: (IslandActivity) -> Boolean) =
        _activities.update { cur -> cur.filterValues { !predicate(it) } }

    fun get(id: String): IslandActivity? = _activities.value[id]
}

/** Pure helper (unit-tested). */
object PeekDedupe {
    const val WINDOW_MS = 10_000L
    fun signature(pkg: String, title: CharSequence?, text: CharSequence?): String =
        pkg + "|" + title?.toString()?.trim()?.lowercase() + "|" + text?.toString()?.trim()?.lowercase()
}
