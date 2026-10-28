package com.ajit.dynamicisland.module.ai

import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * Shows up in Dynamic Island → Modules. Shortcuts: AI Engine, Try it. Tile: which model is ready.
 * Also posts model-download progress to the island (DownloadService).
 */
class AiModule : IslandModuleService() {
    companion object {
        const val ARG_TRY = "try"
        fun refreshTile() { (current as? AiModule)?.postTile() }
    }

    override val tools get() = listOf(
        ModuleTool("open", "AI Engine", R.drawable.ic_spark, MainActivity::class.java),
        ModuleTool("try", "Try AI", R.drawable.ic_spark, TryActivity::class.java),
    )
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() { postTile() }

    fun postTile() {
        val m = Models.active(this)
        val b = IslandTile.Builder(this).icon(R.drawable.ic_spark)
        if (m == null) b.title("AI Engine").text("No model yet. Download one to use AI on this phone.")
        else b.title("AI Engine").text("${m.label} ready · works offline")
        b.tapActivity(MainActivity::class.java).activityAction("open", if (m == null) "Download a model" else "Open", MainActivity::class.java)
        island.postTile(b.build())
    }
}
