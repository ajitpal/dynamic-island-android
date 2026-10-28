package com.ajit.dynamicisland.module.updater

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.updater.hig.Hig

/**
 * Software Update (modelled on iPhone Settings → General → Software Update): what's installed, what's
 * new, Update All, and automatic checks. Nothing installs without a tap here and Android's own prompt.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var hig: Hig
    private lateinit var content: LinearLayout
    private val listener = Updater.Listener { if (!isFinishing) render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        val page = hig.page("Software Update")
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.column.addView(content, hig.mp())
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i
        }
        Updater.restore(this)
        if (UPrefs.auto(this) && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && savedInstanceState == null)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        Updater.addListener(listener)
        CheckJob.schedule(this)
        // Opening the screen checks, unless it checked in the last few minutes or is busy installing.
        val s = Updater.state
        if (!Updater.installing && s !is Updater.State.Checking &&
            (s !is Updater.State.Available || System.currentTimeMillis() - s.checkedAt > 10 * 60_000L) &&
            System.currentTimeMillis() - UPrefs.lastCheck(this) > 5 * 60_000L) Updater.check(this)
    }

    override fun onPause() { Updater.removeListener(listener); super.onPause() }

    private fun render(s: Updater.State) {
        content.removeAllViews()
        when (s) {
            Updater.State.Idle, Updater.State.Checking -> checking()
            is Updater.State.UpToDate -> upToDate(s)
            is Updater.State.Available -> available(s)
            is Updater.State.Failed -> failed(s)
        }
        automatic()
    }

    private fun installedIsland() = Updater.installedVersion(this, Repo.ISLAND_PACKAGE)?.second

    private fun checked(t: Long) = if (t <= 0) "Not checked yet" else
        "Checked " + DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().let { if (it.startsWith("0 ")) "just now" else it }

    private fun checking() {
        val s = hig.section(content)
        val spin = ProgressBar(this).apply { isIndeterminate = true }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
        s.add(hig.row("Checking for Update…", leading = spin).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
    }

    private fun upToDate(s: Updater.State.UpToDate) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(hig.dp(16), hig.dp(28), hig.dp(16), hig.dp(8)) }
        box.addView(hig.tile(R.drawable.ic_check, 0xFF34C759.toInt(), 56))
        box.addView(hig.text("Dynamic Island ${installedIsland() ?: ""}".trim(), Hig.Style.TITLE3).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(14), 0, 0); isAccessibilityHeading = true })
        box.addView(hig.text("The island and your modules are up to date.", Hig.Style.BODY, hig.secondary).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(4), 0, 0) })
        box.addView(hig.text(checked(s.checkedAt), Hig.Style.FOOTNOTE, hig.secondary).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(4), 0, 0) })
        content.addView(box, hig.mp())
        hig.section(content).add(hig.action("Check Now") { Updater.check(this) })
    }

    private fun failed(s: Updater.State.Failed) {
        val sec = hig.section(content, footer = checked(s.checkedAt))
        sec.add(hig.row("Couldn't Check for Updates", s.message, hig.tile(R.drawable.ic_update, 0xFF8E8E93.toInt())))
        sec.add(hig.action("Try Again") { Updater.check(this) })
    }

    private fun available(s: Updater.State.Available) {
        if (!packageManager.canRequestPackageInstalls()) {
            hig.section(content, footer = "Android asks once whether Updater may install apps. You still confirm every update.")
                .add(hig.row("Allow Updater to Install Apps", "Needed to install updates", hig.tile(R.drawable.ic_update, 0xFFFF9500.toInt()), chevron = true, onClick = {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }))
        }
        val sec = hig.section(content, "Dynamic Island ${s.release.version}", footer = checked(s.checkedAt))
        s.items.forEach { item -> sec.add(itemRow(item)) }

        val busy = Updater.installing
        val left = s.items.count { it.actionable }
        if (left > 0) {
            val label = when { busy -> "Updating…"; left == 1 -> "Update"; else -> "Update All" }
            val btn = hig.primary(label) { Updater.installAll(this) }.apply { isEnabled = !busy; alpha = if (busy) 0.5f else 1f }
            content.addView(btn, hig.mp().apply { topMargin = hig.dp(18); marginStart = hig.dp(16); marginEnd = hig.dp(16) })
            hig.note(content, "Android asks you to confirm each app. Modules update first, then the island, then Updater.")
        }

        if (s.release.notes.isNotBlank()) {
            val n = hig.section(content, "What's New", action = "Full Notes" to {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Repo.RELEASES_PAGE))) }
            })
            n.add(hig.text(notes(s.release.notes), Hig.Style.SUBHEAD).apply { setPadding(hig.dp(16), hig.dp(12), hig.dp(16), hig.dp(12)); setTextIsSelectable(true) })
        }
    }

    private fun itemRow(item: Updater.Item): View {
        val a = item.update.app
        val icon: View = runCatching { packageManager.getApplicationIcon(a.pkg) }.getOrNull()?.let { hig.iconView(it, 40) }
            ?: hig.tile(R.drawable.ic_update, 0xFF8E8E93.toInt(), 40)
        val size = if (a.size > 0) " · " + android.text.format.Formatter.formatShortFileSize(this, a.size) else ""
        val sub = when (item.phase) {
            Updater.Phase.WAITING -> "Installed ${item.update.installedVersion}$size"
            Updater.Phase.DOWNLOADING -> "Downloading… ${(item.progress * 100).toInt()}%"
            Updater.Phase.VERIFYING -> "Checking the download…"
            Updater.Phase.CONFIRM -> "Confirm the install when Android asks"
            Updater.Phase.INSTALLING -> "Installing…"
            Updater.Phase.DONE -> "Updated"
            Updater.Phase.FAILED -> item.error ?: "Couldn't update"
        }
        val trailing: View? = when (item.phase) {
            Updater.Phase.DONE -> hig.tile(R.drawable.ic_check, 0xFF34C759.toInt(), 24)
            Updater.Phase.FAILED -> if (!item.canRetry) null else hig.link("Try Again", Hig.Style.SUBHEAD) {
                if (item.error == Updater.NEEDS_PERMISSION) startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                else Updater.installAll(this, a.pkg)
            }
            else -> null
        }
        val row = hig.row("${a.name} ${a.versionName}", sub, icon, trailing = trailing,
            titleColor = hig.label)
        if (item.phase == Updater.Phase.FAILED) ((row.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? android.widget.TextView)?.setTextColor(hig.red)
        if (item.phase == Updater.Phase.DOWNLOADING) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(row, hig.mp())
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = (item.progress * 100).toInt(); contentDescription = "Downloading ${a.name}"
                progressTintList = android.content.res.ColorStateList.valueOf(hig.color(R.color.hig_blue_fill))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(hig.color(R.color.hig_fill))
            }, hig.mp().apply { marginStart = hig.dp(70); marginEnd = hig.dp(16); bottomMargin = hig.dp(8) })
            return col
        }
        return row
    }

    /** The release notes without Markdown symbols. */
    private fun notes(md: String) = md.lines().joinToString("\n") { l ->
        l.replace(Regex("^#+\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ").replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1").replace("`", "")
    }.trim().let { if (it.length > 1500) it.take(1500).substringBeforeLast('\n') + "\n…" else it }

    private fun automatic() {
        val off = !UPrefs.enabledByIsland(this)
        val sec = hig.section(content, "Automatic Updates", footer = if (off)
            "Updater is turned off in Dynamic Island → Modules, so it doesn't check. Turn it on there to resume."
        else "Once a day Updater asks GitHub which versions exist. Nothing about you or your phone is sent. Downloads only start when you tap Update.")
        sec.add(hig.toggle("Check Automatically", UPrefs.auto(this) && !off, null) { on -> UPrefs.setAuto(this, on) }.apply {
            if (off) { alpha = 0.5f; isEnabled = false; for (i in 0 until childCount) getChildAt(i).isEnabled = false }
        })
    }
}
