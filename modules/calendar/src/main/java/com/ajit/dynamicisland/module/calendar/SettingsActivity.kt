package com.ajit.dynamicisland.module.calendar

import android.Manifest
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Date

class SettingsActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var status: TextView
    private lateinit var upcoming: TextView
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        fun t(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); this.alpha = alpha; setPadding(0, dp(6), 0, dp(6))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { c.addView(it) }

        t("Island Calendar", 28f, bold = true)
        t("Your next meeting in the Dynamic Island: a countdown, a pop-up a few minutes before with a one-tap Join for Meet/Zoom/Teams/Webex, and progress while it runs. Your calendar never leaves this phone, and the island app itself never sees it.", 14f, alpha = 0.75f)
        status = t("", 15f, bold = true)
        c.addView(MaterialButton(this).apply { text = "Allow calendar access"; setOnClickListener { ask.launch(Manifest.permission.READ_CALENDAR) } })
        c.addView(MaterialButton(this).apply { text = "Open Dynamic Island → Modules"; setOnClickListener { HostVerifier.openHostModules(this@SettingsActivity) } })

        t("Show the next meeting this many minutes before", 16f, bold = true)
        val lbl = t("", 14f)
        c.addView(Slider(this).apply {
            valueFrom = 5f; valueTo = 60f; stepSize = 5f; value = Prefs.leadMinutes(this@SettingsActivity).toFloat()
            lbl.text = "${value.toInt()} minutes"
            addOnChangeListener { _, v, _ -> lbl.text = "${v.toInt()} minutes"; Prefs.setLeadMinutes(this@SettingsActivity, v.toInt()); (IslandModuleService.current as? CalendarModule)?.refresh() }
        })
        c.addView(MaterialSwitch(this).apply {
            text = "Pop the island open 5 minutes before"; isChecked = Prefs.popUp(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setPopUp(this@SettingsActivity, on) }
        })
        t("Coming up", 16f, bold = true)
        upcoming = t("", 14f, alpha = 0.85f)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val perm = Calendars.hasPermission(this)
        val connected = IslandModuleService.current?.island?.isConnected == true
        status.text = when {
            !HostVerifier.isHostInstalled(this) -> "⚠ Dynamic Island isn't installed"
            !perm -> "⚠ Calendar access not allowed yet"
            !connected -> "Approve this module in Dynamic Island → Modules"
            else -> "✓ Connected to the island"
        }
        val tf = DateFormat.getTimeFormat(this)
        val list = Calendars.upcoming(this, windowMs = 3 * 24 * 3600_000L).take(4)
        upcoming.text = if (!perm) "—" else if (list.isEmpty()) "No meetings in the next 3 days" else list.joinToString("\n") {
            "• ${android.text.format.DateUtils.formatDateTime(this, it.begin, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY)} ${tf.format(Date(it.begin))}  ${it.title}" + (it.provider?.let { p -> "  ($p)" } ?: "")
        }
        (IslandModuleService.current as? CalendarModule)?.refresh()
    }
}
