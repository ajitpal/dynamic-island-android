package com.ajit.dynamicisland.module.voice

import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ModuleTool

/** Connects to the island: adds a "Record" tool and handles the recording card's buttons. */
class VoiceModule : IslandModuleService() {
    override val tools get() = listOf(ModuleTool("record", "Record meeting", R.drawable.ic_mic, RecordActivity::class.java))

    override fun onAction(cardId: String, actionId: String, input: String?) {
        if (cardId == RecorderService.CARD_REC) RecorderService.command(this, actionId)
    }
}
