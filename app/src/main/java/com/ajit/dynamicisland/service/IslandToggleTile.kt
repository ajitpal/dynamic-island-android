package com.ajit.dynamicisland.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ajit.dynamicisland.core.Prefs

/** Quick Settings tile: turn the island on or off from the notification shade. */
class IslandToggleTile : TileService() {

    override fun onStartListening() { super.onStartListening(); update() }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        prefs.set(Prefs.ISLAND_ON, !prefs.islandOn)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val prefs = Prefs(this)
        val ready = IslandAccessibilityService.running
        t.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            prefs.islandOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        t.subtitle = when {
            !ready -> "Not set up"
            prefs.islandOn -> "On"
            else -> "Off"
        }
        t.contentDescription = "Dynamic Island, ${t.subtitle}"
        t.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile after the switch changed somewhere else. */
        fun refresh(ctx: Context) = runCatching {
            requestListeningState(ctx, ComponentName(ctx, IslandToggleTile::class.java))
        }
    }
}
