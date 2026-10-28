package com.ajit.dynamicisland.module.speech

import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/** Speech Pack in the island: a tile with its status and a shortcut to its screen. */
class SpeechModule : IslandModuleService() {
    override val tools = listOf(ModuleTool("open", "Speech Pack", R.drawable.ic_wave, MainActivity::class.java))
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() { instance = this; refresh() }
    override fun onIslandDisconnected() { if (instance === this) instance = null }

    fun refresh() {
        if (!island.isConnected) return
        val m = Models.active(this)
        island.postTile(IslandTile.Builder(this).icon(R.drawable.ic_wave).title("Hindi Speech")
            .text(when {
                DownloadService.current != null -> "Downloading the ${DownloadService.current!!.label} model…"
                m == null -> "Download a model to transcribe Hindi meetings"
                else -> "${m.label} model ready · works offline"
            }).tapActivity(MainActivity::class.java).build())
    }

    companion object {
        @Volatile private var instance: SpeechModule? = null
        fun refreshTile() { instance?.refresh() }
    }
}
