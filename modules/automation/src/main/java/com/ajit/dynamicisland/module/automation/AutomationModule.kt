package com.ajit.dynamicisland.module.automation

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ModuleTool
import java.security.SecureRandom

/**
 * Bridges automation apps to the island.
 *
 * In:  broadcast com.ajit.dynamicisland.automation.SHOW / REMOVE (explicit to this package) with extra
 *      "key" = your secret key. See SettingsActivity for every supported extra.
 * Out: broadcast com.ajit.dynamicisland.automation.TOOL (extra "tool") when an island tool is tapped,
 *      and com.ajit.dynamicisland.automation.EVENT (extras "card", "action", "input") for card buttons.
 */
class AutomationModule : IslandModuleService() {

    override val mainActivity: Class<*> get() = SettingsActivity::class.java

    override val tools: List<ModuleTool>
        get() = listOf(
            ModuleTool("a", Store.label(this, "a"), R.drawable.ic_bolt),
            ModuleTool("b", Store.label(this, "b"), R.drawable.ic_star),
        )

    override fun onIslandConnected() {
        pending.forEach { show(this, it) }
        pending.clear()
    }

    override fun onToolTapped(toolId: String) {
        sendBroadcast(Intent(ACTION_TOOL).putExtra("tool", toolId))
        island.post(
            IslandCard.Builder(this, "tool").title(Store.label(this, toolId)).text("Sent to your automation app")
                .compact("Sent").icon(R.drawable.ic_check).accent(0xFF34C759.toInt()).style(IslandContract.STYLE_ALERT)
                .expiresIn(2000).build()
        )
    }

    override fun onAction(cardId: String, actionId: String, input: String?) {
        sendBroadcast(Intent(ACTION_EVENT).putExtra("card", cardId).putExtra("action", actionId).putExtra("input", input))
    }

    companion object {
        const val ACTION_SHOW = "com.ajit.dynamicisland.automation.SHOW"
        const val ACTION_REMOVE = "com.ajit.dynamicisland.automation.REMOVE"
        const val ACTION_TOOL = "com.ajit.dynamicisland.automation.TOOL"
        const val ACTION_EVENT = "com.ajit.dynamicisland.automation.EVENT"
        private val pending = ArrayList<Bundle>()

        private val ICONS = mapOf(
            "bolt" to R.drawable.ic_bolt, "bell" to R.drawable.ic_bell, "check" to R.drawable.ic_check,
            "home" to R.drawable.ic_home, "car" to R.drawable.ic_car, "timer" to R.drawable.ic_timer, "star" to R.drawable.ic_star,
        )

        /** Build a card from intent extras and post it (or queue it until the island connects). */
        fun show(ctx: Context, ex: Bundle) {
            val m = IslandModuleService.current as? AutomationModule
            if (m == null || !m.island.isConnected) { if (pending.size < 10) pending += ex; return }
            val title = ex.getString("title") ?: return
            val b = IslandCard.Builder(m, ex.getString("id") ?: "auto")
                .title(title).text(ex.getString("text")).compact(ex.getString("compact"))
                .icon(ICONS[ex.getString("icon")] ?: R.drawable.ic_bolt)
                .accent(parseColor(ex.getString("accent")))
            when (ex.getString("style")) {
                "alert" -> b.style(IslandContract.STYLE_ALERT)
                "progress" -> b.style(IslandContract.STYLE_PROGRESS)
            }
            if (ex.containsKey("progress")) b.progress(ex.getInt("progress", 0) / 100f)
            val countdown = ex.getInt("countdown", 0)
            if (countdown > 0) b.countdownTo(System.currentTimeMillis() + countdown * 1000L)
            if (ex.getBoolean("countup", false)) b.countUpFrom(System.currentTimeMillis())
            if (ex.getBoolean("peek", false)) b.peek()
            val seconds = ex.getInt("seconds", 0)
            if (seconds > 0) b.expiresIn(seconds * 1000L)
            ex.getString("tap_url")?.let { b.tapUrl(it) }
            for (n in 1..3) {
                val label = ex.getString("button$n") ?: continue
                b.action("b$n", label, if (n == 1) ActionStyle.POSITIVE else ActionStyle.NORMAL, ex.getString("button${n}_url"))
            }
            ex.getString("reply")?.let { b.inputAction("reply", it, ex.getString("reply_hint") ?: "Type…") }
            b.version(ex.hashCode())
            m.island.post(b.build())
        }

        fun remove(id: String?) {
            (IslandModuleService.current as? AutomationModule)?.island?.remove(id ?: "auto")
        }

        private fun parseColor(s: String?): Int = try { if (s.isNullOrBlank()) 0xFF0A84FF.toInt() else Color.parseColor(s) } catch (e: Exception) { 0xFF0A84FF.toInt() }
    }
}

/** Per-install secret key and tool labels. */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("automation", Context.MODE_PRIVATE)

    fun key(c: Context): String = sp(c).getString("key", null) ?: newKey(c)

    fun newKey(c: Context): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        val k = (1..10).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        sp(c).edit().putString("key", k).apply()
        return k
    }

    fun label(c: Context, tool: String) = sp(c).getString("label_$tool", null) ?: if (tool == "a") "Automation A" else "Automation B"
    fun setLabel(c: Context, tool: String, v: String) = sp(c).edit().putString("label_$tool", v.take(18)).apply()

    /** Constant-time compare so the key can't be guessed by timing. */
    fun check(c: Context, given: String?): Boolean {
        val k = key(c)
        if (given == null || given.length != k.length) return false
        var diff = 0
        for (i in k.indices) diff = diff or (k[i].code xor given[i].code)
        return diff == 0
    }
}
