package com.ajit.dynamicisland.module.voice

import android.text.format.DateUtils
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * Connects to the island. Shortcuts: Record, Recordings, Ask last meeting (island tools and the Modules tab).
 * Tile on the Island tab: the latest meeting with Record / Recordings.
 */
class VoiceModule : IslandModuleService() {
    companion object {
        const val ARG_LAST_ASK = "last:ask"
        /** Refresh the tile after a recording or new notes. */
        fun refreshTile() { (current as? VoiceModule)?.postTile() }
    }

    override val tools get() = listOf(
        ModuleTool("record", "Record meeting", R.drawable.ic_mic, RecordActivity::class.java),
        ModuleTool("recordings", "Recordings", R.drawable.ic_list, MainActivity::class.java),
        ModuleTool("ask", "Ask last meeting", R.drawable.ic_ask, TranscriptActivity::class.java),
    )
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() { postTile() }

    override fun onAction(cardId: String, actionId: String, input: String?) {
        if (cardId == RecorderService.CARD_REC) RecorderService.command(this, actionId)
    }

    fun postTile() {
        Thread {
            val last = Recordings.all(this).firstOrNull()
            val b = IslandTile.Builder(this).icon(R.drawable.ic_mic)
            if (last == null) {
                b.title("Meeting Notes").text("No meetings yet. Tap Record to start.")
            } else {
                val notes = NotesStore.load(this, last.id)
                val actions = notes?.sections?.firstOrNull { it.first == "Action items" || it.first == "Follow-ups" || it.first == "Next" }?.second?.size ?: 0
                val when_ = DateUtils.getRelativeTimeSpanString(last.startedAt).toString()
                b.title(last.title).text(when_ + " · " + when {
                    notes != null && actions > 0 -> "notes ready · $actions action items"
                    notes != null -> "notes ready"
                    else -> "${Notes.clock(last.durationMs)} recorded"
                }).tapActivity(TranscriptActivity::class.java, last.id)
            }
            b.activityAction("record", "Record", RecordActivity::class.java)
                .activityAction("list", "Recordings", MainActivity::class.java)
            island.postTile(b.build())
        }.start()
    }
}
