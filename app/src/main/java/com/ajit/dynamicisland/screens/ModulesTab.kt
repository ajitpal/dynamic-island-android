package com.ajit.dynamicisland.screens

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.modules.Catalog
import com.ajit.dynamicisland.core.modules.ModuleInfo
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.core.modules.ModuleUpdates
import com.ajit.dynamicisland.core.modules.Trust
import com.ajit.dynamicisland.ui.hig.Hig
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Tab 2: one card per installed module with the direct links it provides (its shortcuts) and a ⋯ menu,
 * then modules you can add, then developer options.
 */
class ModulesTab(private val a: AppCompatActivity, private val hig: Hig) : Tab {
    private val prefs = Prefs(a)
    private val page = hig.page("Modules", "Add-on apps with their own permissions. Each one adds shortcuts here and in your island tools.")
    override val root: View get() = page.root
    private val dynamic = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    /** When set, scroll to this module's card on the next refresh. */
    var focusPkg: String? = null

    init { page.column.addView(dynamic, hig.mp()) }

    /** Draws what the registry knows. Never rescans: rescanning publishes, and publishing triggers a refresh. */
    override fun refresh() {
        dynamic.removeAllViews()
        val mods = ModuleRegistry.modules.value
        if (mods.isEmpty()) hig.note(dynamic, "No modules installed yet. Pick one below to add it.")
        updates(mods)
        var focusView: View? = null
        mods.forEach { m -> val v = card(m); if (m.pkg == focusPkg) focusView = v }
        getMore(mods.map { it.pkg }.toSet())
        developer()
        focusView?.let { v -> page.scroll.post { page.scroll.smoothScrollTo(0, v.top) } }
        focusPkg = null
    }

    private fun card(m: ModuleInfo): View {
        val trustText = when (m.trust) {
            Trust.SAME_DEVELOPER -> "Verified · same developer"
            Trust.APPROVED_DEVELOPER -> "Verified · approved developer"
            Trust.UNVERIFIED -> "Unverified developer"
        }
        val s = hig.section(dynamic)
        val more = ImageView(a).apply {
            setImageResource(R.drawable.ic_more); setColorFilter(hig.blue); contentDescription = "More options for ${m.label}"
            setPadding(hig.dp(12), hig.dp(12), hig.dp(12), hig.dp(12)); isClickable = true
            setOnClickListener { menu(this, m) }
        }
        s.add(hig.row(m.label, "${if (m.version.isNotEmpty()) "v${m.version} · " else ""}$trustText", hig.iconView(m.icon, 40), trailing = more,
            onClick = if (m.approved) ({ ModuleRegistry.openModule(a, m) }) else null))
        ModuleUpdates.load(a).firstOrNull { it.pkg == m.pkg }?.let { u ->
            s.add(hig.row("Update available · v${u.version}", "Installed v${m.version}", titleColor = hig.blue, chevron = true, onClick = { openUpdates() }))
        }
        if (m.description.isNotBlank()) s.add(hig.text(m.description, Hig.Style.SUBHEAD, hig.secondary).apply { setPadding(hig.dp(16), hig.dp(10), hig.dp(16), hig.dp(10)) })

        when {
            !m.approved -> s.add(hig.action(if (m.trust == Trust.UNVERIFIED) "Review and turn on…" else "Turn on") { approve(m) })
            m.trust == Trust.UNVERIFIED && !prefs.modulesDevMode -> s.add(hig.row("Blocked: unverified developer", "Turn on \"Allow unverified modules\" below to use it", titleColor = hig.orange))
            !m.connected -> s.add(hig.row("Connecting…", "Open the module once if this doesn't change", titleColor = hig.secondary))
            else -> {
                // The direct links this module provides.
                val tools = ModuleRegistry.tools.value.filter { it.pkg == m.pkg }
                // A shortcut that opens the module's main screen replaces the generic "Open".
                val links = mutableListOf<View>()
                val opensMain = tools.any { it.activity != null && it.activity == m.mainActivity }
                if (!opensMain) links += hig.pill("Open", filled = true) { ModuleRegistry.openModule(a, m) }
                tools.forEach { t -> links += hig.pill(t.label, filled = links.isEmpty()) { ModuleRegistry.toolTapped(t.fullId) } }
                s.add(hig.flow(links))
            }
        }
        return s.container
    }

    /** Like the App Store's Updates: what the Updater module found, with one button to install them there. */
    private fun updates(mods: List<ModuleInfo>) {
        val ups = ModuleUpdates.load(a)
        if (ups.isEmpty()) return
        val s = hig.section(dynamic, if (ups.size == 1) "1 Update Available" else "${ups.size} Updates Available",
            footer = "Updates are downloaded, checked and installed by the Updater module. Android asks you to confirm each app.")
        s.add(hig.row(ups.joinToString(", ") { "${it.name} ${it.version}" }, null, titleColor = hig.label))
        s.add(hig.action(if (ups.size == 1) "Update" else "Update All") { openUpdates() })
    }

    private fun openUpdates() {
        if (!ModuleUpdates.openUpdater(a)) Setup.tryStart(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/releases/latest")))
    }

    /**
     * Android's own uninstall confirmation. Before 2.2.1 the island lacked REQUEST_DELETE_PACKAGES, so
     * Android ignored the request without a word. If it still can't be opened, App info has Uninstall.
     */
    private fun uninstall(m: ModuleInfo) {
        val ok = try { a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${m.pkg}"))); true } catch (e: Exception) { false }
        if (!ok) {
            android.widget.Toast.makeText(a, "Tap Uninstall on the next screen", android.widget.Toast.LENGTH_LONG).show()
            Setup.openAppInfo(a, m.pkg)
        }
    }

    private fun menu(anchor: View, m: ModuleInfo) {
        val pm = PopupMenu(a, anchor, Gravity.END)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (m.approved) items += "Open" to { ModuleRegistry.openModule(a, m) }
        if (m.settings != null) items += "Settings" to { ModuleRegistry.openSettings(a, m) }
        items += "Permissions and storage" to { Setup.openAppInfo(a, m.pkg) }
        if (m.launcherToggle) items += (if (m.launcherVisible) "Hide icon from app drawer" else "Show icon in app drawer") to {
            ModuleRegistry.setLauncherVisible(m.pkg, !m.launcherVisible)
        }
        val tools = ModuleRegistry.tools.value.filter { it.pkg == m.pkg }
        tools.forEach { t ->
            val shown = ModuleRegistry.isToolShown(t.fullId)
            items += (if (shown) "Remove \"${t.label}\" from island tools" else "Add \"${t.label}\" to island tools") to { ModuleRegistry.setToolShown(t.fullId, !shown); refresh() }
        }
        if (m.approved) items += "Turn off" to { ModuleRegistry.setApproved(m.pkg, false) }
        items += "Uninstall…" to { uninstall(m) }
        items.forEachIndexed { i, (t, _) -> pm.menu.add(0, i, i, t) }
        pm.setOnMenuItemClickListener { items[it.itemId].second(); true }
        pm.show()
    }

    private fun approve(m: ModuleInfo) {
        val perms = if (m.permissions.isEmpty()) "No special permissions." else m.permissions.joinToString("\n") { (n, g) -> (if (g) "• " else "○ ") + n }
        MaterialAlertDialogBuilder(a)
            .setTitle("Turn on ${m.label}?")
            .setMessage((if (m.trust == Trust.UNVERIFIED) "⚠ This module isn't from a verified developer.\n\n" else "") +
                "It can show cards and add shortcuts in your island. It can't read your notifications or the Inbox.\n\nIts permissions:\n$perms\n\nCertificate ${m.certSha256.take(16)}…")
            .setPositiveButton("Turn on") { _, _ -> ModuleRegistry.setApproved(m.pkg, true) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Modules listed in the catalog that aren't installed yet. */
    private fun getMore(installed: Set<String>) {
        // The Updater first: it keeps everything else current.
        val missing = Catalog.load(a).filter { it.pkg !in installed }.sortedBy { if (it.pkg.endsWith(".updater")) 0 else 1 }
        if (missing.isEmpty()) return
        val updated = if (Catalog.live) " List updated " + android.text.format.DateFormat.getMediumDateFormat(a).format(java.util.Date(Catalog.serial * 1000)) + " by the Updater." else ""
        val s = hig.section(dynamic, "Get more", "Downloads open in your browser. After installing, the module turns on by itself if it's from this developer.$updated")
        missing.forEach { c ->
            s.add(hig.row(c.name, c.summary, hig.text(c.icon, Hig.Style.TITLE2).apply { gravity = Gravity.CENTER; minWidth = hig.dp(40) },
                value = "Get", valueColor = hig.blue, onClick = {
                    Setup.tryStart(a, Intent(Intent.ACTION_VIEW, Uri.parse(c.download)))
                }))
        }
    }

    private fun developer() {
        val s = hig.section(dynamic, "Developers",
            "Anyone can build a module, with or without an AI coding agent. Modules from unverified developers stay off unless you allow them here.")
        s.add(hig.toggle("Add new modules automatically", prefs.modulesAuto, "Same developer as this app only") { on -> prefs.set(Prefs.MODULES_AUTO, on) })
        s.add(hig.toggle("Allow unverified modules", prefs.modulesDevMode, "Only for modules you trust or build yourself") { on ->
            if (on && !prefs.modulesDevMode) {
                MaterialAlertDialogBuilder(a).setTitle("Allow unverified modules?")
                    .setMessage("They still can't read your notifications, but they can show cards in your island. Only continue for modules you trust.")
                    .setPositiveButton("Allow") { _, _ -> prefs.set(Prefs.MODULES_DEV_MODE, true); ModuleRegistry.rescan(); refresh() }
                    .setNegativeButton("Cancel") { _, _ -> refresh() }.show()
            } else { prefs.set(Prefs.MODULES_DEV_MODE, on); ModuleRegistry.rescan() }
        })
        s.add(hig.nav("Build a module", R.drawable.ic_code, Hig.Tile.GRAY) {
            Setup.tryStart(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/blob/main/docs/MODULES.md")))
        })
    }
}
