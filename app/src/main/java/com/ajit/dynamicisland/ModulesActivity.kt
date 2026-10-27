package com.ajit.dynamicisland

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.modules.ModuleInfo
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.core.modules.Trust
import com.ajit.dynamicisland.ui.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/** Installed add-on modules: who made them, what they can access, approve / disable / settings. */
class ModulesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        root.addView(text("Modules", 30f, bold = true))
        root.addView(text("Add-on apps that give the island new abilities. Each module is a separate app with its own permissions: you only grant what you install, and you approve every module here before it can show anything.", 14f, secondary = true).apply { setPadding(0, dp(6), 0, dp(14)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Get modules"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/releases"))) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Build your own module"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/blob/main/docs/MODULES.md"))) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(text("Developer mode", 18f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) })
        root.addView(text("Allows modules from unverified developers, e.g. a module you are building yourself. Only turn this on for modules you trust.", 13f, secondary = true))
        root.addView(MaterialSwitch(this).apply {
            text = "Allow unverified modules"
            isChecked = prefs.modulesDevMode
            setOnCheckedChangeListener { b, on ->
                if (on && !prefs.modulesDevMode) {
                    b.isChecked = false
                    MaterialAlertDialogBuilder(this@ModulesActivity)
                        .setTitle("Allow unverified modules?")
                        .setMessage("Unverified modules come from developers who are not on Dynamic Island's approved list. They still can't read your notifications, but they can show cards in your island. Only continue for modules you trust.")
                        .setPositiveButton("Allow") { _, _ -> prefs.set(Prefs.MODULES_DEV_MODE, true); b.isChecked = true; ModuleRegistry.rescan() }
                        .setNegativeButton("Cancel", null).show()
                } else if (!on) { prefs.set(Prefs.MODULES_DEV_MODE, false); ModuleRegistry.rescan() }
            }
        })

        setContentView(scroll)
        ModuleRegistry.start(this)
        lifecycleScope.launch { ModuleRegistry.modules.collect { render(it) } }
    }

    override fun onResume() { super.onResume(); ModuleRegistry.rescan() }

    private fun render(mods: List<ModuleInfo>) {
        list.removeAllViews()
        if (mods.isEmpty()) {
            list.addView(text("No modules installed yet.\nInstall a module APK (e.g. Automation Bridge or Calendar), then come back here to approve it.", 15f, secondary = true).apply { setPadding(0, dp(12), 0, dp(12)) })
            return
        }
        mods.forEach { list.addView(card(it)) }
    }

    private fun card(m: ModuleInfo): View {
        val card = MaterialCardView(this).apply { radius = dp(20).toFloat(); cardElevation = 0f }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12)) }
        card.addView(col)
        col.addView(text(m.label, 18f, bold = true))
        val trustColor = when (m.trust) { Trust.SAME_DEVELOPER, Trust.APPROVED_DEVELOPER -> 0xFF34C759.toInt(); Trust.UNVERIFIED -> 0xFFFF9F0A.toInt() }
        col.addView(text((if (m.trust == Trust.UNVERIFIED) "⚠ " else "✓ ") + m.trust.label + (m.developer?.let { " · $it" } ?: ""), 13f, bold = true).apply { setTextColor(trustColor); setPadding(0, dp(2), 0, dp(6)) })
        if (m.description.isNotBlank()) col.addView(text(m.description, 14f))
        val perms = if (m.permissions.isEmpty()) "No special permissions" else m.permissions.joinToString("\n") { (n, g) -> (if (g) "• " else "○ ") + n + if (g) "" else " (not granted yet)" }
        col.addView(text("Permissions\n$perms", 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(text("Status: " + when {
            m.connected -> "connected"
            m.approved && m.trust == Trust.UNVERIFIED && !prefs.modulesDevMode -> "blocked: unverified (enable Developer mode)"
            m.approved -> "connecting…"
            else -> "not approved"
        } + " · cert ${m.certSha256.take(12)}…", 12f, secondary = true).apply { setPadding(0, dp(6), 0, dp(4)) })

        col.addView(MaterialSwitch(this).apply {
            text = "Approve this module"
            isChecked = m.approved
            setOnCheckedChangeListener { _, on ->
                if (on && !m.approved) {
                    MaterialAlertDialogBuilder(this@ModulesActivity)
                        .setTitle("Approve ${m.label}?")
                        .setMessage("It will be able to show cards and add tools in your island. It can NOT read your notifications or the Inbox.\n\n$perms")
                        .setPositiveButton("Approve") { _, _ -> ModuleRegistry.setApproved(m.pkg, true) }
                        .setNegativeButton("Cancel") { _, _ -> isChecked = false }
                        .setOnCancelListener { isChecked = false }
                        .show()
                } else if (!on && m.approved) ModuleRegistry.setApproved(m.pkg, false)
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (m.settings != null) row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Settings"; setOnClickListener { ModuleRegistry.openSettings(this@ModulesActivity, m) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Uninstall"; setOnClickListener { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${m.pkg}"))) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)); addView(card) }
    }

    private fun text(t: String, sp: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (secondary) alpha = 0.7f
    }
}
