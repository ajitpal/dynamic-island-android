package com.example.islandmodule

import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * A complete, working module: one island tool, one card with buttons, one tile on the island app's
 * Island tab. Replace the example logic with your idea (see AGENTS.md).
 */
class HelloModule : IslandModuleService() {

    /** Shortcuts: shown in the island's tools panel and on your card in the Modules tab. */
    override val tools = listOf(
        ModuleTool("hello", "Say hello", R.drawable.ic_module),
        ModuleTool("open", "Hello settings", R.drawable.ic_module, MainActivity::class.java),
    )

    /** "Open" on the Modules tab goes here. */
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() {
        island.postTile(IslandTile.Builder(this).title("Hello module").text("Tap Say hello in your island tools")
            .icon(R.drawable.ic_module).activityAction("open", "Settings", MainActivity::class.java).build())
    }

    override fun onToolTapped(toolId: String) {
        if (toolId != "hello") return
        island.post(IslandCard.Builder(this, "hello")
            .title("Hello from a module")
            .text("Cards are drawn by the island in the user's theme.")
            .icon(R.drawable.ic_module).accent(0xFF0A84FF.toInt())
            .action("wave", "Wave back", ActionStyle.POSITIVE)
            .expiresIn(10_000).peek()
            .build())
    }

    override fun onAction(cardId: String, actionId: String, input: String?) {
        if (cardId == "hello" && actionId == "wave") island.remove("hello")
    }
}
