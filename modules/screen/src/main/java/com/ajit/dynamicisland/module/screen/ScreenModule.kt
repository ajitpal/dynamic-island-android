package com.ajit.dynamicisland.module.screen

import android.text.format.DateUtils
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * Connects to the island. Shortcuts: Record screen (starts or stops), Recordings.
 * Tile on the Island tab: the latest recording with Record / Recordings.
 */
class ScreenModule : IslandModuleService() {
    companion object {
        fun refreshTile() { (current as? ScreenModule)?.postTile() }
    }

    override val tools get() = listOf(
        ModuleTool("record", "Record screen", R.drawable.ic_screen_rec, CaptureActivity::class.java),
        ModuleTool("recordings", "Recordings", R.drawable.ic_list, MainActivity::class.java),
    )
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() { postTile(); RecorderService.instance?.repost() }

    override fun onAction(cardId: String, actionId: String, input: String?) {
        if (cardId == RecorderService.CARD) RecorderService.command(this, actionId)
    }

    fun postTile() {
        Thread {
            val last = Recordings.all(this, 1).firstOrNull()
            val b = IslandTile.Builder(this).icon(R.drawable.ic_screen_rec)
            if (last == null) b.title("Screen Recorder").text("No recordings yet. Tap Record to start.")
            else b.title("Last recording").text(DateUtils.getRelativeTimeSpanString(last.addedMs).toString() + " · " + Plan.clock(last.durationMs))
                .tapActivity(OpenActivity::class.java, OpenActivity.arg(OpenActivity.PLAY, last.uri))
            b.activityAction("record", "Record", CaptureActivity::class.java)
                .activityAction("list", "Recordings", MainActivity::class.java)
            island.postTile(b.build())
        }.start()
    }
}
