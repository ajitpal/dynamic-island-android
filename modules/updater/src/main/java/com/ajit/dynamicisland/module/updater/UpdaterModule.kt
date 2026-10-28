package com.ajit.dynamicisland.module.updater

import android.text.format.DateUtils
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/** The Updater in the island: a "Software Update" shortcut, a tile with the status, a card when updates arrive. */
class UpdaterModule : IslandModuleService() {

    override val tools = listOf(ModuleTool("update", "Software Update", R.drawable.ic_update, MainActivity::class.java))
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() {
        instance = this
        // The island only connects to modules that are turned on.
        UPrefs.setEnabledByIsland(this, true)
        CheckJob.schedule(this)
        Updater.restore(this)
        refreshTile()
    }

    override fun onIslandDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun refreshTile() {
        if (!island.isConnected) return
        val n = UPrefs.availableCount(this)
        val last = UPrefs.lastCheck(this)
        val b = IslandTile.Builder(this).icon(R.drawable.ic_update).title("Software Update").tapActivity(MainActivity::class.java)
        when {
            n > 0 -> b.text("${if (n == 1) "1 update" else "$n updates"} available · Dynamic Island ${UPrefs.availableRelease(this) ?: ""}".trim())
                .activityAction("view", if (n == 1) "Update" else "Update All", MainActivity::class.java)
            last > 0 -> b.text("Up to date · checked ${DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}".replace("0 minutes ago", "just now"))
            else -> b.text(if (UPrefs.auto(this)) "Checks once a day" else "Automatic checks are off")
        }
        island.postTile(b.build())
    }

    fun announce(av: Updater.State.Available) {
        if (!island.isConnected) return
        val n = av.items.size
        island.post(IslandCard.Builder(this, "available")
            .title(if (n == 1) "Update Available" else "$n Updates Available")
            .text(av.items.joinToString(", ") { "${it.update.app.name} ${it.update.app.versionName}" })
            .icon(R.drawable.ic_update).accent(0xFF8E8E93.toInt())
            .tapActivity(MainActivity::class.java)
            .activityAction("view", "View", MainActivity::class.java)
            .peek(5000).expiresIn(6 * 3600_000L).build())
        refreshTile()
    }

    companion object {
        @Volatile var instance: UpdaterModule? = null
    }
}
