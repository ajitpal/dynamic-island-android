package com.ajit.dynamicisland.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.IslandDebug
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.core.tools.ToolCatalog
import com.ajit.dynamicisland.core.tools.ToolLayout
import com.ajit.dynamicisland.core.tools.ToolNeed
import com.ajit.dynamicisland.service.IslandAccessibilityService
import com.ajit.dynamicisland.service.IslandNotificationListener
import com.ajit.dynamicisland.ui.Themes
import com.ajit.dynamicisland.ui.circle
import com.ajit.dynamicisland.ui.hig.Hig
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The pages behind each Settings row. Each builds into [column]; [refresh] rebuilds it. */
class SettingsPages(private val a: AppCompatActivity, private val hig: Hig, private val column: LinearLayout) {
    private val prefs = Prefs(a)
    private fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()

    fun build(key: String) {
        column.removeAllViews()
        when (key) {
            PAGE_LOOK -> look()
            PAGE_SHOWS -> shows()
            PAGE_BEHAVIOUR -> behaviour()
            PAGE_APPS -> apps()
            PAGE_TOOLS -> tools()
            PAGE_INBOX -> inbox()
            PAGE_SMART -> smart()
            PAGE_POPUPS -> popups()
            PAGE_GESTURES -> gestures()
            PAGE_DIAGNOSTICS -> diagnostics()
        }
    }

    private fun sw(s: Hig.Section, title: String, key: String, def: Boolean, subtitle: String? = null) =
        s.add(hig.toggle(title, prefs.sp.getBoolean(key, def), subtitle) { prefs.set(key, it) })

    private fun slider(s: Hig.Section, title: String, key: String, from: Float, to: Float, def: Float, unit: String = "") =
        s.add(hig.slider(title, from, to, prefs.sp.getInt(key, def.toInt()).toFloat(), format = { "${it.toInt()}$unit" }) { prefs.setInt(key, it.toInt()) })

    // ---- Look and fit ----
    private fun look() {
        val t = hig.section(column, "Theme")
        Themes.all.forEach { th ->
            t.add(hig.row(th.name, leading = View(a).apply { background = circle(th.bg).apply { setStroke(hig.dp(1), 0x55888888) }; layoutParams = LinearLayout.LayoutParams(hig.dp(28), hig.dp(28)) },
                trailing = if (prefs.theme == th.id) hig.text("✓", Hig.Style.HEADLINE, hig.blue) else null,
                onClick = { prefs.sp.edit().putString(Prefs.THEME, th.id).apply(); build(PAGE_LOOK) }))
        }
        val size = hig.section(column, "Size and shape", "Sizes start from your phone's status bar and camera hole. The real island updates while you drag.")
        slider(size, "Width", Prefs.WIDTH_SCALE, 60f, 180f, 100f, "%")
        slider(size, "Height", Prefs.HEIGHT_SCALE, 60f, 150f, 100f, "%")
        slider(size, "Corner roundness", Prefs.ROUNDNESS, 0f, 100f, 100f, "%")
        slider(size, "Opacity", Prefs.OPACITY, 20f, 100f, 100f, "%")
        sw(size, "Glow outline in the activity's colour", Prefs.OUTLINE, false)
        slider(size, "Animation speed", Prefs.ANIM_SPEED, 50f, 200f, 100f, "%")
        val fit = hig.section(column, "Invisible at rest",
            "With nothing running, the island shrinks to exactly your camera hole. Turn on Calibrate (the hole turns red), line it up with the sliders, then turn Calibrate off.")
        sw(fit, "Calibrate", Prefs.CALIBRATE, false, "Show the resting island in red")
        slider(fit, "Camera hole size", Prefs.HOLE_ADJUST, -20f, 30f, 0f, " px")
        slider(fit, "Move sideways", Prefs.OFFSET_X, -60f, 60f, 0f, " dp")
        slider(fit, "Move down", Prefs.OFFSET_Y, -20f, 40f, 0f, " dp")
        val r = hig.section(column)
        r.add(hig.action("Reset look and position", destructive = true) { prefs.resetLook(); build(PAGE_LOOK) })
    }

    // ---- What shows ----
    private fun shows() {
        val s = hig.section(column, "Show in the island")
        sw(s, "Messages and quick reply", Prefs.F_MESSAGES, true)
        sw(s, "Now playing", Prefs.F_MEDIA, true, "Any music or video app")
        sw(s, "Calls", Prefs.F_CALLS, true, "Phone, WhatsApp, Telegram…")
        sw(s, "Timers and stopwatches", Prefs.F_TIMERS, true)
        sw(s, "Navigation", Prefs.F_NAV, true)
        sw(s, "Downloads, deliveries, uploads", Prefs.F_PROGRESS, true)
        sw(s, "Live scores, rides, Live Updates", Prefs.F_LIVE, true)
        sw(s, "Payment confirmations", Prefs.F_PAYMENTS, true)
        sw(s, "Screen and voice recording", Prefs.F_RECORDING, true)
        sw(s, "Every other notification", Prefs.F_ALL, true)
        val sys = hig.section(column, "System")
        sw(sys, "Charging, silent, Bluetooth, flashlight", Prefs.F_SYSTEM, true)
        sw(sys, "Internet lost and back, hotspot, NFC", Prefs.CONNECTIVITY, true)
        sw(sys, "Camera and microphone in-use dots", Prefs.F_PRIVACY_DOTS, true)
        sw(sys, "Unlock animation", Prefs.UNLOCK_ANIM, true)
    }

    // ---- Behaviour ----
    private fun behaviour() {
        val s = hig.section(column, "Tap and expand")
        sw(s, "Tap to preview", Prefs.TAP_PREVIEW, true, "Tap again or Open to open the app")
        sw(s, "Pop open for new messages and calls", Prefs.PEEK, true)
        slider(s, "Preview time", Prefs.PEEK_SECONDS, 2f, 10f, 5f, " s")
        sw(s, "Haptic feedback", Prefs.HAPTICS, true)
        val h = hig.section(column, "Hide", "Accessibility: if Android's \"Time to take action\" is set longer, previews stay open at least that long.")
        sw(h, "Show the idle pill around the camera", Prefs.SHOW_IDLE, true)
        sw(h, "Hide message text on the lock screen", Prefs.PRIVACY_LOCK, true)
        sw(h, "Hide an app's activity while it's open", Prefs.HIDE_FG_APP, true)
        sw(h, "Hide in landscape", Prefs.HIDE_LANDSCAPE, true)
        sw(h, "Hide in full-screen apps", Prefs.HIDE_FULLSCREEN, false, "Experimental")
    }

    // ---- Apps ----
    private fun apps() {
        val pkgs = prefs.seenApps().toList().map { it to NotificationParser.appLabel(a, it) }.sortedBy { it.second.lowercase() }
        if (pkgs.isEmpty()) { hig.note(column, "Apps appear here after they post their first notification."); return }
        val blocked = prefs.blockedApps().toMutableSet()
        val s = hig.section(column, "Allowed in the island", "Turn an app off to keep it out of the island. It still notifies as usual.")
        pkgs.forEach { (pkg, label) ->
            s.add(hig.row(label, leading = hig.iconView(NotificationParser.appIcon(a, pkg), 30),
                trailing = com.google.android.material.materialswitch.MaterialSwitch(a).apply {
                    isChecked = pkg !in blocked; contentDescription = label
                    setOnCheckedChangeListener { _, on -> if (on) blocked -= pkg else blocked += pkg; prefs.setBlocked(blocked) }
                }))
        }
    }

    // ---- Island tools ----
    private fun tools() {
        val ids = prefs.tools
        val perPage = prefs.toolsPerPage
        ToolLayout.pages(ids, perPage).forEachIndexed { p, pageIds ->
            val s = hig.section(column, "Page ${p + 1}", if (p == 0) "Long-press the empty island to open your tools; swipe sideways for more pages." else null)
            if (pageIds.isEmpty()) s.add(hig.row("No tools", titleColor = hig.secondary))
            pageIds.forEach { id ->
                val i = ids.indexOf(id)
                val (label, icon, tint) = toolLabelIcon(id)
                val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                fun btn(res: Int, desc: String, enabled: Boolean, f: () -> Unit) = btns.addView(ImageButton(a).apply {
                    setImageResource(res); contentDescription = "$desc $label"; isEnabled = enabled; alpha = if (enabled) 1f else 0.3f
                    setColorFilter(hig.blue); setBackgroundColor(0); setOnClickListener { f() }
                }, LinearLayout.LayoutParams(hig.dp(44), hig.dp(48)))
                btn(R.drawable.ic_up, "Move up", i > 0) { prefs.tools = ToolLayout.move(ids, i, i - 1); build(PAGE_TOOLS) }
                btn(R.drawable.ic_down, "Move down", i < ids.size - 1) { prefs.tools = ToolLayout.move(ids, i, i + 1); build(PAGE_TOOLS) }
                btn(R.drawable.ic_close, "Remove", true) { prefs.tools = ids - id; build(PAGE_TOOLS) }
                val lead = if (tint) hig.tile(iconRes(id), Hig.Tile.INDIGO) else hig.iconView(icon, 30)
                s.add(hig.row(label, leading = lead, trailing = btns))
            }
        }
        val add = hig.section(column)
        add.add(hig.action("Add tools…") { addToolDialog() })
        add.add(hig.action("Add an app…") { addAppDialog() })
        add.add(hig.action("Reset to default", destructive = true) { prefs.sp.edit().remove(Prefs.TOOLS).apply(); build(PAGE_TOOLS) })
        val per = hig.section(column)
        slider(per, "Tools per page", Prefs.TOOLS_PER_PAGE, 3f, 8f, 6f)
    }

    private fun iconRes(id: String) = ToolCatalog.def(id)?.icon ?: R.drawable.ic_apps

    private fun toolLabelIcon(id: String): Triple<String, android.graphics.drawable.Drawable?, Boolean> {
        if (id.startsWith(ModuleRegistry.TOOL_PREFIX)) {
            val t = ModuleRegistry.tool(id)
            return Triple(t?.label ?: "Module tool (not connected)", t?.icon, false)
        }
        return if (ToolCatalog.isApp(id)) {
            val pkg = ToolCatalog.appPackage(id)
            Triple(NotificationParser.appLabel(a, pkg), NotificationParser.appIcon(a, pkg), false)
        } else Triple(ToolCatalog.def(id)?.label ?: id, null, true)
    }

    private fun addToolDialog() {
        val current = prefs.tools.toSet()
        ModuleRegistry.start(a)
        val builtIn = ToolCatalog.builtIn.filter { it.id !in current }
            .map { it.id to "${it.label}  ·  ${it.category}" + if (it.need == ToolNeed.DND_ACCESS) " (asks for DND access)" else "" }
        val fromModules = ModuleRegistry.tools.value.filter { it.fullId !in current }
            .map { t -> t.fullId to "${t.label}  ·  ${NotificationParser.appLabel(a, t.pkg)}" }
        val available = builtIn + fromModules
        if (available.isEmpty()) { toast("All tools are already added"); return }
        val checked = BooleanArray(available.size)
        MaterialAlertDialogBuilder(a).setTitle("Add tools")
            .setMultiChoiceItems(available.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Add") { _, _ -> prefs.tools = prefs.tools + available.filterIndexed { i, _ -> checked[i] }.map { it.first }; build(PAGE_TOOLS) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addAppDialog() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = a.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(a.packageManager).toString() }
            .filter { it.first != a.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        MaterialAlertDialogBuilder(a).setTitle("Add an app")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> prefs.tools = prefs.tools + (ToolCatalog.APP_PREFIX + apps[i].first); build(PAGE_TOOLS) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Inbox ----
    private fun inbox() {
        val s = hig.section(column, footer = "Every notification is kept here, encrypted on your phone, until you clear it. Open it from the Island tab, by swiping down on the empty island, or with the Inbox tool.")
        sw(s, "Keep notifications in the Inbox", Prefs.INBOX_ENABLED, true)
        slider(s, "Keep for", Prefs.INBOX_DAYS, 1f, 30f, 7f, " days")
        val o = hig.section(column)
        o.add(hig.action("Open the Inbox") { a.startActivity(Intent(a, com.ajit.dynamicisland.InboxActivity::class.java)) })
    }

    // ---- Smart Inbox ----
    private fun smart() {
        val ai = com.ajit.dynamicisland.sdk.ai.AiClient
        val footer = when {
            !ai.isInstalled(a) -> "Install the AI Engine module to use these. Everything runs on your phone."
            !ai.isTrusted(a) -> "⚠ The installed AI Engine isn't signed by the same developer as this app, so Smart Inbox won't use it."
            else -> "Uses the AI Engine module on this phone. When a feature is on, the text of the notifications involved goes to that module only, never to the internet. One-time codes are masked first."
        }
        val s = hig.section(column, footer = footer)
        sw(s, "Catch me up", Prefs.SMART_DIGEST, false, "A summary of what's unread, in the Inbox and as an island tool")
        sw(s, "Suggest replies", Prefs.SMART_REPLIES, false, "✨ next to Reply in the island")
        if (!ai.isInstalled(a)) hig.section(column).add(hig.action("Get the AI Engine") {
            Setup.tryStart(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/releases/latest")))
        })
    }

    // ---- Duplicate pop-ups ----
    private fun popups() {
        val rec = hig.section(column, "Recommended",
            "Android doesn't let any app hide another app's pop-up banner. Turn off \"Pop-up\" (on Xiaomi/POCO \"Floating notifications\") for an app: it still notifies, and the island replaces the banner.")
        rec.add(hig.action("Turn off pop-ups for an app…") { popupSettingsDialog() })
        rec.add(hig.action("Copy ADB command for all apps") {
            a.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                android.content.ClipData.newPlainText("adb", "adb shell settings put global heads_up_notifications_enabled 0"))
            toast("Copied. Run it from a computer with USB debugging on. Use 1 instead of 0 to undo.")
        })
        val adv = hig.section(column, "Advanced",
            "For the apps you pick, the island takes the notification out of the shade, so it lives only in the island and the Inbox. Replies and Open still work. Calls and ongoing notifications are never moved.")
        adv.add(hig.action("Move notifications into the island…") { moveAppsDialog() })
    }

    private fun seenAppsSorted() = prefs.seenApps().toList().map { it to NotificationParser.appLabel(a, it) }.sortedBy { it.second.lowercase() }

    private fun popupSettingsDialog() {
        val apps = seenAppsSorted()
        if (apps.isEmpty()) { toast("No apps yet: wait for a few notifications to arrive"); return }
        MaterialAlertDialogBuilder(a).setTitle("Turn off pop-ups for…")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                toast("Turn off \"Pop-up\" / \"Floating notifications\" on the next screen")
                Setup.tryStart(a, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, apps[i].first),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${apps[i].first}")))
            }.setNegativeButton("Close", null).show()
    }

    private fun moveAppsDialog() {
        val apps = seenAppsSorted()
        if (apps.isEmpty()) { toast("No apps yet: wait for a few notifications to arrive"); return }
        val chosen = prefs.moveApps().toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        MaterialAlertDialogBuilder(a).setTitle("Move into the island")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> if (on) chosen += apps[i].first else chosen -= apps[i].first }
            .setPositiveButton("Save") { _, _ -> prefs.setMoveApps(chosen) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Gestures ----
    private fun gestures() {
        val s = hig.section(column, "On an activity")
        listOf("Tap" to "Preview it (or open the app, see Behaviour)", "Long-press or swipe down" to "Expand, music controls",
            "Swipe up or sideways" to "Shrink back to the pill", "Tap the side bubble" to "Switch between two activities")
            .forEach { (g, d) -> s.add(hig.row(g, d)) }
        val e = hig.section(column, "On the empty island")
        listOf("Long-press" to "Your tools (swipe sideways for more pages)", "Swipe down" to "Inbox",
            "Long-press a module tool" to "Shortcuts and options for that module", "Tap" to "Bring back shrunk activities")
            .forEach { (g, d) -> e.add(hig.row(g, d)) }
    }

    // ---- Diagnostics ----
    private fun diagnostics() {
        val acts = IslandRepository.activities.value.values
        val d = IslandDebug
        val rows = listOf(
            "Accessibility running" to IslandAccessibilityService.running.toString(),
            "Listener connected" to IslandNotificationListener.connected.toString(),
            "Overlay attached" to d.overlayAttached.toString(),
            "Island mode" to d.mode, "Hidden because" to d.hiddenReason,
            "Status bar (overlay)" to d.statusBarReported.toString(), "Foreground app" to d.foreground,
            "Last peek" to d.lastPeek, "Last notification" to d.lastNotification, "Geometry" to d.geometry,
            "Modules" to ModuleRegistry.summary(),
            "Activities (${acts.size})" to acts.joinToString { it.kind.name + ":" + it.title }.ifEmpty { "none" },
            "Phone" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
        )
        val s = hig.section(column, footer = "If something doesn't appear, take a screenshot of this page and attach it to a GitHub issue.")
        rows.forEach { (k, v) -> s.add(hig.row(k, v.toString())) }
        val c = hig.section(column)
        c.add(hig.action("Copy all") {
            a.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                android.content.ClipData.newPlainText("diagnostics", rows.joinToString("\n") { "${it.first}: ${it.second}" }))
            toast("Copied")
        })
        c.add(hig.action("Refresh") { build(PAGE_DIAGNOSTICS) })
    }

    companion object {
        const val PAGE_LOOK = "look"; const val PAGE_SHOWS = "shows"; const val PAGE_BEHAVIOUR = "behaviour"; const val PAGE_APPS = "apps"
        const val PAGE_TOOLS = "tools"; const val PAGE_INBOX = "inbox"; const val PAGE_SMART = "smart"; const val PAGE_POPUPS = "popups"
        const val PAGE_GESTURES = "gestures"; const val PAGE_DIAGNOSTICS = "diagnostics"
        val TITLES = mapOf(PAGE_LOOK to "Look and fit", PAGE_SHOWS to "What shows", PAGE_BEHAVIOUR to "Behaviour", PAGE_APPS to "Apps",
            PAGE_TOOLS to "Island tools", PAGE_INBOX to "Inbox", PAGE_SMART to "Smart Inbox", PAGE_POPUPS to "Duplicate pop-ups",
            PAGE_GESTURES to "Gestures", PAGE_DIAGNOSTICS to "Diagnostics")
    }
}
