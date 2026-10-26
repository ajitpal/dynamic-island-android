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
        if (a.peek && changed) _peeks.tryEmit(a.id)
        if (a.expiresAt > 0) {
            val delay = (a.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 30
            handler.postDelayed({ expire(a.id) }, delay)
        }
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
