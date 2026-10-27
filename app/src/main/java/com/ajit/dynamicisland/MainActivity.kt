package com.ajit.dynamicisland

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.core.Demo
import com.ajit.dynamicisland.core.IslandTools
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.service.IslandAccessibilityService
import com.ajit.dynamicisland.ui.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Setup, permissions, OEM keep-alive tweaks, demos and settings — all on one scrolling screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var statusNotif: TextView
    private lateinit var statusAccess: TextView
    private lateinit var statusBt: TextView
    private lateinit var diag: TextView
    private lateinit var toolsList: LinearLayout

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom); insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (::toolsList.isInitialized) renderTools()
    }

    // ------------------------------------------------------------------ UI

    private fun build() {
        content.addView(text("Dynamic Island", 32f, bold = true))
        content.addView(text("Live activities around your camera — music, calls, timers, messages, downloads and quick tools.", 15f, secondary = true).apply { setPadding(0, dp(6), 0, dp(16)) })

        // 0. Restricted settings (side-loaded apps on Android 13+)
        section("0 · Unlock restricted settings (one time)")
        hint("Android greys out notification & accessibility access for apps installed from a file. This is a system safety switch — only you can lift it:\n" +
            "  1. Tap \"Notification access\" below and try to switch it on (it will say \"Controlled by restricted setting\" — that's expected).\n" +
            "  2. Come back and tap \"Open App info\".\n" +
            "  3. Tap ⋮ (top-right) → \"Allow restricted settings\" → confirm with your PIN / fingerprint.\n" +
            "  4. Now step 1 works. Repeat only if you uninstall the app.")
        button("Open App info") { openAppInfo() }

        // 1. Setup
        section("1 · Turn it on")
        card {
            statusNotif = statusRow(it, "Notification access", "Reads messages, calls, timers, media sessions") { disclose { openNotificationAccess() } }
            statusAccess = statusRow(it, "Accessibility service", "Draws the island over the status bar and lock screen") { disclose { openAccessibility() } }
            statusBt = statusRow(it, "Nearby devices (optional)", "Shows the name of Bluetooth earbuds when they connect") {
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // 2. Keep alive (brand-aware)
        val brand = Brand.detect()
        section("2 · Keep it running in the background")
        hint("Many phone makers stop background apps to save battery. Detected: ${brand.label}. Do the steps below, otherwise the island may disappear after a while.")
        if (brand == Brand.XIAOMI) {
            button("Autostart → allow") { openAutostart() }
            button("Battery saver → No restrictions") { openBattery() }
            button("Other permissions → Lock screen / pop-ups") { openMiuiPermissions() }
        } else {
            brand.autostart?.let { comp -> button("Auto-launch / background start → allow") { tryStart(Intent().setComponent(comp), appDetails()) } }
            button("Battery → Unrestricted / Don't optimise") { tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails()) }
            button("App info (battery, notifications)") { openAppInfo() }
        }
        button("Guide for ${brand.label} on dontkillmyapp.com") {
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${brand.slug}")))
        }
        hint("Tip: also open Recents, long-press Dynamic Island and lock it.")

        // 3. Try it
        section("3 · Try it")
        val chips = ChipGroup(this)
        fun chip(label: String, action: () -> Unit) = chips.addView(Chip(this).apply {
            text = label; setOnClickListener { requireRunning(); action() }
        })
        chip("💬 Message") { Demo.message(this) }
        chip("📞 Incoming call") { Demo.incomingCall(this) }
        chip("⬇️ Download") { Demo.download(this) }
        chip("⏱ 10 s timer") { IslandTools.startTimer(this, 10_000) }
        chip("⏲ Stopwatch") { IslandTools.startStopwatch(this) }
        chip("⚡ Charging") { Demo.charging(this) }
        chip("💳 Payment") { Demo.payment(this) }
        chip("🎧 Earbuds") { Demo.earbuds(this) }
        chip("🏏 Live score") { Demo.liveScore(this) }
        chip("⏺ Screen rec") { Demo.recording(this) }
        chip("🔓 Unlock") { Demo.unlock(this) }
        chip("✖ Clear demos") { Demo.clear() }
        content.addView(chips)
        hint("Gestures: tap = preview (or open the app, see Behaviour) · long-press = expand / music controls · swipe up or sideways = shrink back to the pill · tap the side bubble = switch activity · empty island: swipe down = Inbox, long-press = your tools (swipe sideways for more pages).")

        // Island tools (customisable)
        section("Island tools")
        hint("Long-press the empty island to open your tools. Add, remove and reorder them here; swipe sideways in the island to change pages.")
        toolsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card { it.addView(toolsList) }
        renderTools()
        val toolBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolBtns.addView(MaterialButton(this).apply { text = "Add tool"; setOnClickListener { addToolDialog() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        toolBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Add app"; setOnClickListener { addAppDialog() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        toolBtns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset"; setOnClickListener { prefs.sp.edit().remove(Prefs.TOOLS).apply(); renderTools() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(toolBtns)
        slider("Tools per page", Prefs.TOOLS_PER_PAGE, 3f, 8f, 6f)

        // Notification inbox
        section("Notification inbox")
        hint("Every notification is kept here, encrypted on your phone, until you clear it. Open it by swiping down on the empty island, from the Inbox tool, or long-press the app icon → Inbox.")
        card { switch(it, "Keep notifications in the Inbox", Prefs.INBOX_ENABLED, true) }
        slider("Keep for (days)", Prefs.INBOX_DAYS, 1f, 30f, 7f)
        button("Open Inbox") { startActivity(Intent(this, InboxActivity::class.java)) }

        // Pop-up duplicates
        section("Stop duplicate pop-ups")
        hint("Android doesn't let any app hide another app's pop-up banner. Two ways to avoid seeing it twice:")
        content.addView(text("Recommended: turn off pop-ups in system settings", 15f, bold = true).apply { setPadding(0, dp(8), 0, dp(2)) })
        hint("Notifications still arrive, ring and stay in your shade. Only the banner is replaced by the island. On Xiaomi/POCO this is called \"Floating notifications\".")
        button("Turn off pop-ups for an app…") { popupSettingsDialog() }
        button("Copy ADB command (all apps, some phones)") {
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                android.content.ClipData.newPlainText("adb", "adb shell settings put global heads_up_notifications_enabled 0"))
            toast("Copied. Run it from a computer with USB debugging on. Use 1 instead of 0 to undo.")
        }
        content.addView(text("Advanced: move notifications into the island", 15f, bold = true).apply { setPadding(0, dp(14), 0, dp(2)) })
        hint("For the apps you pick, the island takes the notification out of the shade, so it only lives in the island and the Inbox. Replies and Open still work. A banner may still flash briefly, and calls and ongoing notifications are never moved.")
        button("Choose apps to move…") { moveAppsDialog() }

        // 4. Pinned note
        section("4 · Pin a note to the island")
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "e.g. Gate B12 · Boarding 18:40" }
        val et = TextInputEditText(til.context).apply { setText(prefs.note) }
        til.addView(et)
        content.addView(til)
        val noteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        noteRow.addView(MaterialButton(this).apply { text = "Pin"; setOnClickListener { IslandTools.pinNote(this@MainActivity, et.text?.toString().orEmpty()); toast("Pinned") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        noteRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Unpin"; setOnClickListener { et.setText(""); IslandTools.pinNote(this@MainActivity, "") } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(noteRow)

        // 5. Features
        section("5 · What shows in the island")
        card {
            switch(it, "Messages & quick reply", Prefs.F_MESSAGES, true)
            switch(it, "Now playing (any music / video app)", Prefs.F_MEDIA, true)
            switch(it, "Calls (phone, WhatsApp, Telegram…)", Prefs.F_CALLS, true)
            switch(it, "Timers & stopwatches", Prefs.F_TIMERS, true)
            switch(it, "Navigation (Google Maps…)", Prefs.F_NAV, true)
            switch(it, "Downloads, deliveries, uploads", Prefs.F_PROGRESS, true)
            switch(it, "System: charging, silent, Bluetooth, flashlight", Prefs.F_SYSTEM, true)
            switch(it, "Camera / mic in-use dots", Prefs.F_PRIVACY_DOTS, true)
            switch(it, "Live scores, cab & food delivery, Android 16 Live Updates", Prefs.F_LIVE, true)
            switch(it, "Payment confirmations (Wallet, GPay, PhonePe, Paytm…)", Prefs.F_PAYMENTS, true)
            switch(it, "Screen & voice recording (red REC)", Prefs.F_RECORDING, true)
            switch(it, "Unlock animation", Prefs.UNLOCK_ANIM, true)
            switch(it, "Internet lost / back, hotspot, NFC", Prefs.CONNECTIVITY, true)
            switch(it, "Every other notification (any app)", Prefs.F_ALL, true)
        }

        // 6. Behaviour
        section("6 · Behaviour")
        card {
            switch(it, "Show the idle pill around the camera", Prefs.SHOW_IDLE, true)
            switch(it, "Auto-expand new messages & calls", Prefs.PEEK, true)
            switch(it, "Tap to preview (tap again or \"Open\" to open the app)", Prefs.TAP_PREVIEW, true)
            switch(it, "Haptic feedback", Prefs.HAPTICS, true)
            switch(it, "Hide message text on the lock screen", Prefs.PRIVACY_LOCK, true)
            switch(it, "Hide in landscape", Prefs.HIDE_LANDSCAPE, true)
            switch(it, "Hide in full-screen apps (experimental)", Prefs.HIDE_FULLSCREEN, false)
            switch(it, "Hide an app's activity while that app is open", Prefs.HIDE_FG_APP, true)
        }

        // Per-app control
        section("Apps")
        hint("Choose which apps may use the island. Apps appear here after they post their first notification.")
        button("Choose apps…") { chooseApps() }

        // 7. Look & fit
        section("7 · Look & fit")
        hint("Sizes start from your phone's status-bar height and camera hole, so they follow the system Display-size setting. Watch the real island at the top while you drag: it updates live.")
        slider("Width (%)", Prefs.WIDTH_SCALE, 60f, 180f, 100f)
        slider("Height (%)", Prefs.HEIGHT_SCALE, 60f, 150f, 100f)
        slider("Corner roundness (%)", Prefs.ROUNDNESS, 0f, 100f, 100f)
        slider("Opacity (%)", Prefs.OPACITY, 20f, 100f, 100f)
        content.addView(text("Theme", 14f).apply { setPadding(0, dp(8), 0, 0) })
        val themeGroup = ChipGroup(this).apply { isSingleSelection = true }
        com.ajit.dynamicisland.ui.Themes.all.forEach { t ->
            themeGroup.addView(Chip(this).apply {
                text = t.name; isCheckable = true; isChecked = prefs.theme == t.id
                chipIcon = com.ajit.dynamicisland.ui.circle(t.bg).apply { setStroke(dp(1), 0x55888888) }
                isChipIconVisible = true
                setOnClickListener { prefs.sp.edit().putString(Prefs.THEME, t.id).apply() }
            })
        }
        content.addView(themeGroup)
        card { switch(it, "Glow outline in the activity's colour", Prefs.OUTLINE, false) }
        slider("Animation speed (%)", Prefs.ANIM_SPEED, 50f, 200f, 100f)

        content.addView(text("Make it invisible at rest", 16f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
        hint("With nothing running, the island shrinks to exactly your camera hole, so you can't see it. Turn on Calibrate (the hole turns red), then use the sliders until the red circle just covers the camera. Turn Calibrate off when done.")
        card { switch(it, "Calibrate (show the resting island in red)", Prefs.CALIBRATE, false) }
        slider("Camera hole size fine-tune (px)", Prefs.HOLE_ADJUST, -20f, 30f, 0f)
        slider("Horizontal offset (dp)", Prefs.OFFSET_X, -60f, 60f, 0f)
        slider("Vertical offset (dp)", Prefs.OFFSET_Y, -20f, 40f, 0f)
        button("Reset look & position") { prefs.resetLook(); recreate() }
        slider("Message preview time (s)", Prefs.PEEK_SECONDS, 2f, 10f, 5f)

        section("Diagnostics")
        hint("If something doesn't appear, screenshot this card and share it.")
        diag = text("", 12f).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        card { it.addView(diag) }
        button("Refresh diagnostics") { refreshStatus() }

        content.addView(text("v${packageManager.getPackageInfo(packageName, 0).versionName} · Open source · Android 12+ phones with a punch-hole or notch", 12f, secondary = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
        })
    }

    private fun section(title: String) = content.addView(text(title, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(8)) })

    private fun hint(t: String) = content.addView(text(t, 13f, secondary = true).apply { setPadding(0, dp(4), 0, dp(8)) })

    private fun button(label: String, onClick: () -> Unit) = content.addView(
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; setOnClickListener { onClick() }
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    )

    private fun card(fill: (LinearLayout) -> Unit) {
        val card = MaterialCardView(this).apply { radius = dp(20).toFloat(); cardElevation = 0f }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        card.addView(inner)
        fill(inner)
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun statusRow(parent: LinearLayout, title: String, sub: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
            isClickable = true
            val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, bold = true))
        texts.addView(text(sub, 13f, secondary = true))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val status = text("", 14f, bold = true)
        row.addView(status)
        parent.addView(row)
        return status
    }

    private fun switch(parent: LinearLayout, label: String, key: String, def: Boolean) {
        parent.addView(MaterialSwitch(this).apply {
            text = label
            isChecked = prefs.sp.getBoolean(key, def)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c -> prefs.set(key, c) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun slider(label: String, key: String, from: Float, to: Float, def: Float) {
        val title = text("", 14f)
        content.addView(title)
        val s = Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = 1f
            value = prefs.sp.getInt(key, def.toInt()).toFloat().coerceIn(from, to)
            title.text = "$label: ${value.toInt()}"
            addOnChangeListener { _, v, _ ->
                title.text = "$label: ${v.toInt()}"
                prefs.setInt(key, v.toInt())
            }
        }
        content.addView(s)
    }

    private fun text(t: String, sp: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (secondary) alpha = 0.7f
    }

    // ------------------------------------------------------------------ status

    private fun notificationAccessGranted() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(this, IslandAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshStatus() {
        fun mark(tv: TextView, ok: Boolean, okText: String = "On") {
            tv.text = if (ok) "✓ $okText" else "Set up ›"
            tv.setTextColor(if (ok) 0xFF34C759.toInt() else 0xFFFF9F0A.toInt())
        }
        mark(statusNotif, notificationAccessGranted())
        mark(statusAccess, accessibilityEnabled())
        val acts = com.ajit.dynamicisland.core.IslandRepository.activities.value.values
        val d = com.ajit.dynamicisland.core.IslandDebug
        diag.text = buildString {
            appendLine("Accessibility running : ${IslandAccessibilityService.running}")
            appendLine("Listener connected    : ${com.ajit.dynamicisland.service.IslandNotificationListener.connected}")
            appendLine("Overlay attached      : ${d.overlayAttached}")
            appendLine("Island mode           : ${d.mode}")
            appendLine("Hidden because        : ${d.hiddenReason}")
            appendLine("Status bar (overlay)  : ${d.statusBarReported}")
            appendLine("Foreground app        : ${d.foreground}")
            appendLine("Last peek             : ${d.lastPeek}")
            appendLine("Last notification     : ${d.lastNotification}")
            appendLine("Geometry              : ${d.geometry}")
            append("Activities (${acts.size}) : ${acts.joinToString { it.kind.name + ":" + it.title }}")
        }
        mark(statusBt, ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Allowed")
    }

    private fun requireRunning() {
        if (!IslandAccessibilityService.running) toast("Turn on the accessibility service first (step 1)")
    }

    // ------------------------------------------------------------------ intents

    private fun tryStart(vararg intents: Intent) {
        for (i in intents) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        toast("Couldn't open that screen on this phone")
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun openNotificationAccess() = tryStart(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, com.ajit.dynamicisland.service.IslandNotificationListener::class.java).flattenToString()
        ),
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    private fun openAccessibility() {
        toast("Find \"Dynamic Island\" under Downloaded apps and switch it on")
        tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() = tryStart(appDetails())

    private fun openAutostart() = tryStart(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetails(),
    )

    private fun openBattery() = tryStart(
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(),
    )

    /** Prominent disclosure shown before sending the user to grant sensitive access. */
    private fun disclose(then: () -> Unit) {
        if (prefs.sp.getBoolean("disclosure_ok", false)) { then(); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("How Dynamic Island uses this access")
            .setMessage(
                "• Notification access: reads notifications and media sessions on this phone only, to show them in the island and let you reply or control playback.\n\n" +
                "• Accessibility service: used only to draw the island above the status bar and lock screen, and to know which app is open so it can hide that app's own activity. It does not read text on your screen.\n\n" +
                "• Nothing leaves your phone: the app has no internet permission, no ads and no analytics."
            )
            .setPositiveButton("Agree & continue") { _, _ -> prefs.set("disclosure_ok", true); then() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openMiuiPermissions() = tryStart(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName),
        appDetails(),
    )

    private fun chooseApps() {
        val pkgs = prefs.seenApps().toList()
            .map { it to com.ajit.dynamicisland.core.NotificationParser.appLabel(this, it) }
            .sortedBy { it.second.lowercase() }
        if (pkgs.isEmpty()) { toast("No apps yet: wait for a few notifications to arrive"); return }
        val blocked = prefs.blockedApps().toMutableSet()
        val checked = BooleanArray(pkgs.size) { pkgs[it].first !in blocked }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Show in the island")
            .setMultiChoiceItems(pkgs.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) blocked -= pkgs[i].first else blocked += pkgs[i].first
            }
            .setPositiveButton("Save") { _, _ -> prefs.setBlocked(blocked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ tools editor

    private fun renderTools() {
        toolsList.removeAllViews()
        val ids = prefs.tools
        if (ids.isEmpty()) toolsList.addView(text("No tools. Tap Add tool.", 14f, secondary = true).apply { setPadding(0, dp(10), 0, dp(10)) })
        val perPage = prefs.toolsPerPage
        ids.forEachIndexed { i, id ->
            if (i > 0 && i % perPage == 0) toolsList.addView(text("— page ${i / perPage + 1} —", 12f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val (label, icon) = toolLabelIcon(id)
            row.addView(android.widget.ImageView(this).apply {
                setImageDrawable(icon)
                if (!com.ajit.dynamicisland.core.tools.ToolCatalog.isApp(id)) setColorFilter(currentTextColor())
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
            row.addView(text(label, 15f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun iconBtn(res: Int, desc: String, enabled: Boolean, onClick: () -> Unit) = row.addView(android.widget.ImageButton(this).apply {
                setImageResource(res); contentDescription = desc; isEnabled = enabled; alpha = if (enabled) 1f else 0.3f
                setColorFilter(currentTextColor()); setBackgroundColor(0)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            iconBtn(R.drawable.ic_up, "Move up", i > 0) { prefs.tools = com.ajit.dynamicisland.core.tools.ToolLayout.move(ids, i, i - 1); renderTools() }
            iconBtn(R.drawable.ic_down, "Move down", i < ids.size - 1) { prefs.tools = com.ajit.dynamicisland.core.tools.ToolLayout.move(ids, i, i + 1); renderTools() }
            iconBtn(R.drawable.ic_close, "Remove", true) { prefs.tools = ids - id; renderTools() }
            toolsList.addView(row)
        }
    }

    private fun currentTextColor(): Int {
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        return androidx.core.content.ContextCompat.getColor(this, tv.resourceId.takeIf { it != 0 } ?: android.R.color.black)
    }

    private fun toolLabelIcon(id: String): Pair<String, android.graphics.drawable.Drawable?> {
        val cat = com.ajit.dynamicisland.core.tools.ToolCatalog
        return if (cat.isApp(id)) {
            val pkg = cat.appPackage(id)
            com.ajit.dynamicisland.core.NotificationParser.appLabel(this, pkg) to com.ajit.dynamicisland.core.NotificationParser.appIcon(this, pkg)
        } else {
            val d = cat.def(id)
            (d?.label ?: id) to d?.let { androidx.core.content.ContextCompat.getDrawable(this, it.icon) }
        }
    }

    private fun addToolDialog() {
        val current = prefs.tools.toSet()
        val available = com.ajit.dynamicisland.core.tools.ToolCatalog.builtIn.filter { it.id !in current }
        if (available.isEmpty()) { toast("All built-in tools are already added"); return }
        val labels = available.map { "${it.label}  ·  ${it.category}" + if (it.need == com.ajit.dynamicisland.core.tools.ToolNeed.DND_ACCESS) " (asks for DND access)" else "" }
        val checked = BooleanArray(available.size)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Add tools")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Add") { _, _ ->
                prefs.tools = prefs.tools + available.filterIndexed { i, _ -> checked[i] }.map { it.id }
                renderTools()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addAppDialog() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Add an app shortcut")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                prefs.tools = prefs.tools + (com.ajit.dynamicisland.core.tools.ToolCatalog.APP_PREFIX + apps[i].first)
                renderTools()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ pop-up duplicates

    private fun seenAppsSorted() = prefs.seenApps().toList()
        .map { it to com.ajit.dynamicisland.core.NotificationParser.appLabel(this, it) }
        .sortedBy { it.second.lowercase() }

    private fun popupSettingsDialog() {
        val apps = seenAppsSorted()
        if (apps.isEmpty()) { toast("No apps yet: wait for a few notifications to arrive"); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Turn off pop-ups for…")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                toast("Turn off \"Pop-up\" / \"Floating notifications\" on the next screen")
                tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, apps[i].first),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${apps[i].first}")))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun moveAppsDialog() {
        val apps = seenAppsSorted()
        if (apps.isEmpty()) { toast("No apps yet: wait for a few notifications to arrive"); return }
        val chosen = prefs.moveApps().toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Move into the island")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton("Save") { _, _ -> prefs.setMoveApps(chosen) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @Suppress("unused")
    private fun View.gone() { visibility = View.GONE }
}

/** Phone maker detection for keep-alive guidance. Component names are best-effort; we fall back to App info. */
enum class Brand(val label: String, val slug: String, val autostart: ComponentName?) {
    XIAOMI("Xiaomi / Redmi / POCO (HyperOS, MIUI)", "xiaomi", null),
    SAMSUNG("Samsung (One UI)", "samsung", ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
    ONEPLUS("OnePlus (OxygenOS)", "oneplus", ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
    OPPO("OPPO / realme (ColorOS, realme UI)", "oppo", ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
    VIVO("vivo / iQOO (Funtouch OS, OriginOS)", "vivo", ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
    HUAWEI("Huawei / Honor", "huawei", null),
    MOTOROLA("Motorola", "motorola", null),
    GOOGLE("Google Pixel", "google", null),
    OTHER("Android", "", null);

    companion object {
        fun detect(): Brand = when (android.os.Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> XIAOMI
            "samsung" -> SAMSUNG
            "oneplus" -> ONEPLUS
            "oppo", "realme" -> OPPO
            "vivo", "iqoo" -> VIVO
            "huawei", "honor" -> HUAWEI
            "motorola" -> MOTOROLA
            "google" -> GOOGLE
            else -> OTHER
        }
    }
}
