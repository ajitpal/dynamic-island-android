package com.ajit.dynamicisland.screens

import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.InboxActivity
import com.ajit.dynamicisland.OnboardingActivity
import com.ajit.dynamicisland.PageActivity
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.Demo
import com.ajit.dynamicisland.core.IslandTools
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.core.inbox.InboxFilter
import com.ajit.dynamicisland.core.inbox.InboxStore
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.core.tools.ToolLayout
import com.ajit.dynamicisland.service.IslandAccessibilityService
import com.ajit.dynamicisland.ui.hig.Hig

/** Tab 1: what the island is doing, whether it's working, the Inbox, module tiles, tools and Try it. */
class IslandTab(private val a: AppCompatActivity, private val hig: Hig) : Tab {
    private val prefs = Prefs(a)
    private val page = hig.page("Island")
    override val root: View get() = page.root
    private val preview = IslandPreview(a)
    private val dynamic = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    init {
        page.column.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hig.dp(96)))
        page.column.addView(dynamic, hig.mp())
    }

    /** Badge for the tab bar: "!" when setup is incomplete, else unread count. */
    fun badge(unread: Int): String? = when {
        !Setup.ready(a) -> "!"
        unread > 0 -> if (unread > 99) "99+" else unread.toString()
        else -> null
    }

    override fun refresh() {
        preview.render()
        dynamic.removeAllViews()
        status()
        inbox()
        modules()
        tools()
        tryIt()
    }

    fun refreshPreview() = preview.render()

    private fun status() {
        val s = hig.section(dynamic)
        when {
            !Setup.ready(a) -> s.add(hig.row("Finish setup",
                listOfNotNull(if (!Setup.notificationAccess(a)) "Notification access" else null, if (!Setup.accessibility(a)) "Accessibility service" else null)
                    .joinToString(" and ") + " still off",
                hig.tile(R.drawable.ic_info, Hig.Tile.ORANGE), chevron = true,
                onClick = { a.startActivity(Intent(a, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, 1)) }))
            !IslandAccessibilityService.running -> s.add(hig.row("The island isn't running",
                "Your phone may have stopped it. Check Keep it running.", hig.tile(R.drawable.ic_battery, Hig.Tile.ORANGE), chevron = true,
                onClick = { a.startActivity(Intent(a, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, 2)) }))
            else -> s.add(hig.row("Dynamic Island is on", "Long-press the empty island for tools; swipe down for the Inbox",
                hig.tile(R.drawable.ic_check, Hig.Tile.GREEN)))
        }
    }

    private fun inbox() {
        val s = hig.section(dynamic, "Inbox", action = "See all" to { a.startActivity(Intent(a, InboxActivity::class.java)) })
        if (!prefs.inboxEnabled) {
            s.add(hig.toggle("Keep notifications in the Inbox", false, "Encrypted on this phone until you clear them") { on -> prefs.set(Prefs.INBOX_ENABLED, on); refresh() })
            return
        }
        val placeholder = hig.row("Loading…", titleColor = hig.secondary)
        s.add(placeholder)
        Thread {
            val items = runCatching { InboxStore.query(a, InboxFilter.UNREAD, null, 3) }.getOrDefault(emptyList())
            val unread = runCatching { InboxStore.unreadCount(a) }.getOrDefault(0)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                (placeholder.parent as? ViewGroup)?.removeView(placeholder)
                s.clear()
                if (prefs.smartDigest && com.ajit.dynamicisland.sdk.ai.AiClient.isTrusted(a))
                    s.add(hig.row("Catch me up", "A short summary of what's unread, made on this phone", hig.tile(R.drawable.ic_spark, Hig.Tile.PURPLE), chevron = true,
                        onClick = { a.startActivity(Intent(a, InboxActivity::class.java).putExtra(InboxActivity.EXTRA_CATCH_UP, true)) }))
                if (items.isEmpty()) s.add(hig.row("No unread notifications", leading = hig.tile(R.drawable.ic_inbox, Hig.Tile.BLUE)))
                items.forEach { e -> s.add(entryRow(e)) }
                if (unread > items.size) s.add(hig.action("Show all $unread unread") { a.startActivity(Intent(a, InboxActivity::class.java)) })
                (a as? com.ajit.dynamicisland.MainActivity)?.setIslandBadge(unread)
            }
        }.start()
    }

    private fun entryRow(e: InboxEntry) = hig.row(
        e.title.ifBlank { e.app }, e.text.take(120), hig.iconView(NotificationParser.appIcon(a, e.pkg), 30),
        value = DateUtils.getRelativeTimeSpanString(e.posted, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
        onClick = { a.startActivity(Intent(a, InboxActivity::class.java)) },
    )

    /** Tiles that modules keep up to date (Meeting Notes: last meeting; AI Engine: model status…). */
    private fun modules() {
        val tiles = ModuleRegistry.tiles.value
        if (tiles.isEmpty()) return
        val s = hig.section(dynamic, "From your modules")
        tiles.forEach { t ->
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            box.addView(hig.row(t.title, t.text, hig.iconView(t.icon, 30), chevron = t.onTap != null, onClick = t.onTap))
            if (t.actions.isNotEmpty()) box.addView(hig.pills(t.actions.mapIndexed { i, act -> hig.pill(act.first, filled = i == 0) { act.second() } }))
            s.add(box)
        }
    }

    private fun tools() {
        val ids = prefs.tools
        val pages = ToolLayout.pages(ids, prefs.toolsPerPage).size
        val s = hig.section(dynamic, "Island tools", "Long-press the empty island to open them.")
        s.add(hig.nav("Your tools", R.drawable.ic_apps, Hig.Tile.INDIGO, value = "${ids.size} on $pages ${if (pages == 1) "page" else "pages"}") {
            PageActivity.open(a, PageActivity.TOOLS)
        })
        s.add(hig.nav("Pinned note", R.drawable.ic_note, Hig.Tile.YELLOW, value = prefs.note.ifBlank { "None" }) { pinNote() })
    }

    private fun pinNote() {
        val til = com.google.android.material.textfield.TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "e.g. Gate B12 · Boarding 18:40" }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply { setText(prefs.note) }
        til.addView(et); til.setPadding(hig.dp(20), hig.dp(8), hig.dp(20), 0)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setTitle("Pin a note to the island").setView(til)
            .setPositiveButton("Pin") { _, _ -> IslandTools.pinNote(a, et.text?.toString().orEmpty()); refresh() }
            .setNeutralButton("Unpin") { _, _ -> IslandTools.pinNote(a, ""); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun tryIt() {
        val s = hig.section(dynamic, "Try it", if (IslandAccessibilityService.running) "Watch the top of the screen." else "Turn on the island first to see these.")
        fun p(t: String, f: () -> Unit) = hig.pill(t) { f() }
        s.add(hig.flow(listOf(
            p("Message") { Demo.message(a) }, p("Incoming call") { Demo.incomingCall(a) }, p("Download") { Demo.download(a) },
            p("10 s timer") { IslandTools.startTimer(a, 10_000) }, p("Stopwatch") { IslandTools.startStopwatch(a) },
            p("Charging") { Demo.charging(a) }, p("Payment") { Demo.payment(a) }, p("Earbuds") { Demo.earbuds(a) },
            p("Live score") { Demo.liveScore(a) }, p("Screen recording") { Demo.recording(a) }, p("Unlock") { Demo.unlock(a) },
            p("Clear") { Demo.clear() },
        )))
    }
}

interface Tab {
    val root: View
    fun refresh()
}
