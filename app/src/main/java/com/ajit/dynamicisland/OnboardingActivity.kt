package com.ajit.dynamicisland

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.core.Demo
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.screens.IslandPreview
import com.ajit.dynamicisland.screens.Setup
import com.ajit.dynamicisland.service.IslandAccessibilityService
import com.ajit.dynamicisland.ui.hig.Hig

/**
 * First run, three short steps (HIG: brief, skippable, permission asked with its reason):
 * 1. Welcome  2. Permissions with live checkmarks  3. Keep it running (phone-brand specific).
 * Shown once; reopen from Settings → Setup.
 */
class OnboardingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_STEP = "step"
        const val PREF_DONE = "onboarding_done"
        fun done(ctx: android.content.Context) = Prefs(ctx).sp.getBoolean(PREF_DONE, false)
    }

    private lateinit var hig: Hig
    private lateinit var body: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var primaryBtn: View
    private lateinit var secondaryBtn: View
    private var step = 0
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { show(step) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        step = savedInstanceState?.getInt(EXTRA_STEP) ?: intent.getIntExtra(EXTRA_STEP, 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(hig.bg) }
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(hig.dp(20), hig.dp(8), hig.dp(20), hig.dp(12)) }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, hig.dp(12)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        bottom.addView(dots, hig.mp())
        primaryBtn = hig.primary("Continue") { next() }
        bottom.addView(primaryBtn, hig.mp())
        secondaryBtn = hig.link("Set up later") { finishOnboarding() }
        bottom.addView(secondaryBtn, hig.mp())
        root.addView(bottom, hig.mp())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step > 0) show(step - 1) else finishOnboarding() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt(EXTRA_STEP, step) }
    override fun onResume() { super.onResume(); show(step) }  // live checkmarks after returning from Settings

    private fun next() { if (step < 2) show(step + 1) else finishOnboarding() }

    private fun finishOnboarding() {
        Prefs(this).set(PREF_DONE, true)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun show(s: Int) {
        step = s.coerceIn(0, 2)
        body.removeAllViews()
        val page = when (step) { 0 -> welcome(); 1 -> permissions(); else -> keepRunning() }
        body.addView(page.root)
        dots.removeAllViews()
        repeat(3) { i ->
            dots.addView(View(this).apply { background = hig.rounded(if (i == step) hig.label else hig.separator, 4) },
                LinearLayout.LayoutParams(hig.dp(8), hig.dp(8)).apply { marginStart = hig.dp(4); marginEnd = hig.dp(4) })
        }
        (primaryBtn as android.widget.TextView).text = when (step) {
            1 -> if (Setup.ready(this)) "Continue" else "Continue without it"
            2 -> "Done"
            else -> "Continue"
        }
        secondaryBtn.visibility = if (step == 2) View.GONE else View.VISIBLE
        page.root.contentDescription = "Step ${step + 1} of 3"
    }

    private fun welcome(): Hig.Page {
        val p = hig.page(null, topPad = 24)
        p.column.addView(IslandPreview(this, demo = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hig.dp(220)))
        p.column.addView(hig.text("Dynamic Island", Hig.Style.LARGE_TITLE).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(24), 0, hig.dp(8)); isAccessibilityHeading = true }, hig.mp())
        p.column.addView(hig.text("Music, calls, timers, messages and quick tools, live around your camera. At rest it hides inside the camera hole.",
            Hig.Style.CALLOUT, hig.secondary).apply { gravity = Gravity.CENTER; setPadding(hig.dp(12), 0, hig.dp(12), 0) }, hig.mp())
        val s = hig.section(p.column, footer = "Nothing leaves your phone: no internet permission, no ads, no analytics.")
        s.add(hig.row("Private", "Everything is processed on this phone", hig.tile(R.drawable.ic_shield, Hig.Tile.GREEN)))
        s.add(hig.row("Yours", "Themes, size, tools and add-on modules", hig.tile(R.drawable.ic_palette, Hig.Tile.PURPLE)))
        s.add(hig.row("Open source", "Read every line on GitHub", hig.tile(R.drawable.ic_code, Hig.Tile.GRAY)))
        return p
    }

    private fun permissions(): Hig.Page {
        val p = hig.page("Turn it on", "Two switches let the island show what your phone is doing. You'll come straight back here.")
        val s = hig.section(p.column, footer = "Notification access reads notifications on this phone only. The accessibility service only draws the island and knows which app is open; it can't read your screen.")
        s.add(hig.row("Notification access", "Messages, calls, timers, music", hig.tile(R.drawable.ic_bell, Hig.Tile.RED),
            trailing = hig.status(Setup.notificationAccess(this)), onClick = { Setup.openNotificationAccess(this) }))
        s.add(hig.row("Accessibility service", "Draws the island over the status bar", hig.tile(R.drawable.ic_tab_island, Hig.Tile.BLUE),
            trailing = hig.status(Setup.accessibility(this)), onClick = { Setup.openAccessibility(this) }))
        s.add(hig.row("Nearby devices", "Optional: shows your earbuds' name", hig.tile(R.drawable.ic_bluetooth, Hig.Tile.BLUE),
            trailing = hig.status(Setup.bluetooth(this), "Allowed", "Allow"), onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }))
        if (!Setup.ready(this)) {
            val h = hig.section(p.column, "Switch greyed out?",
                "Android 13+ blocks these switches for apps installed from a file. In App info, tap ⋮ (top right) → Allow restricted settings, confirm, then come back and try again.")
            h.add(hig.action("Open App info") { Setup.openAppInfo(this) })
        }
        return p
    }

    private fun keepRunning(): Hig.Page {
        val brand = Brand.detect()
        val p = hig.page("Keep it running", "${brand.label} can stop apps in the background to save battery. These switches keep the island alive.")
        val s = hig.section(p.column, footer = "Tip: in Recents, long-press Dynamic Island and lock it.")
        Setup.keepAliveSteps(brand).forEach { st -> s.add(hig.nav(st.title, R.drawable.ic_battery, Hig.Tile.GREEN, subtitle = st.subtitle) { st.run(this) }) }
        s.add(hig.nav("Guide for your phone", R.drawable.ic_info, Hig.Tile.GRAY, subtitle = "dontkillmyapp.com") {
            Setup.tryStart(this, Intent(Intent.ACTION_VIEW, Uri.parse(Setup.guideUrl(brand))))
        })
        val t = hig.section(p.column, "Try it", if (IslandAccessibilityService.running) null else "Turn on the accessibility service first to see these.")
        t.add(hig.flow(listOf(
            hig.pill("Message") { Demo.message(this) },
            hig.pill("Incoming call") { Demo.incomingCall(this) },
            hig.pill("Charging") { Demo.charging(this) },
        )))
        return p
    }
}
