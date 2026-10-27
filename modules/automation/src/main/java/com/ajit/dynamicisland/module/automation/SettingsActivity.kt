package com.ajit.dynamicisland.module.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        fun t(s: String, sp: Float, bold: Boolean = false, mono: Boolean = false, alpha: Float = 1f) = c.addView(TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); this.alpha = alpha; setPadding(0, dp(6), 0, dp(6)); setTextIsSelectable(true)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (mono) typeface = android.graphics.Typeface.MONOSPACE
        })
        fun btn(label: String, onClick: () -> Unit) = c.addView(MaterialButton(this).apply { text = label; setOnClickListener { onClick() } })

        t("Automation Bridge", 28f, bold = true)
        t("Show anything from Tasker, MacroDroid, Automate or adb in your Dynamic Island, and trigger your automations from island tools.", 14f, alpha = 0.75f)

        val status = when {
            !HostVerifier.isHostInstalled(this) -> "⚠ Dynamic Island isn't installed."
            (IslandModuleService.current?.island?.isConnected == true) -> "✓ Connected to the island"
            else -> "Not connected yet. Approve this module in Dynamic Island → Modules."
        }
        t(status, 15f, bold = true)
        btn("Open Dynamic Island → Modules") { if (!HostVerifier.openHostModules(this)) toast("Dynamic Island not found") }

        t("Your secret key", 18f, bold = true)
        t("Every command must include this key, so other apps can't put things in your island.", 13f, alpha = 0.7f)
        val keyView = TextView(this).apply { text = Store.key(this@SettingsActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        c.addView(keyView)
        btn("Copy key") { copy(Store.key(this)) }
        btn("Generate a new key") { keyView.text = Store.newKey(this); toast("New key: update your automations") }
        btn("Send a test card") {
            AutomationModule.show(this, Bundle().apply {
                putString("title", "Hello from Automation Bridge"); putString("text", "Your automations can show cards like this one")
                putString("compact", "Test"); putString("icon", "star"); putBoolean("peek", true); putInt("seconds", 8)
                putString("button1", "Nice")
            })
            toast(if (IslandModuleService.current?.island?.isConnected == true) "Sent" else "Queued: will show once the island connects")
        }

        t("Tool names", 18f, bold = true)
        t("Two tools you can add in Dynamic Island → Island tools. Tapping one broadcasts com.ajit.dynamicisland.automation.TOOL with extra tool=a or b. Changes apply after you re-approve the module.", 13f, alpha = 0.7f)
        for (tool in listOf("a", "b")) {
            val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Tool ${tool.uppercase()} name" }
            val et = TextInputEditText(til.context).apply { setText(Store.label(this@SettingsActivity, tool)) }
            et.setOnFocusChangeListener { _, has -> if (!has) Store.setLabel(this, tool, et.text?.toString().orEmpty().ifBlank { "Automation ${tool.uppercase()}" }) }
            til.addView(et); c.addView(til)
        }

        t("How to send a card", 18f, bold = true)
        t("Send a broadcast intent to package com.ajit.dynamicisland.module.automation, class .CommandReceiver, action com.ajit.dynamicisland.automation.SHOW, with these extras:", 13f, alpha = 0.8f)
        t("""key        your secret key (required)
title      text (required)
text       second line
compact    short text beside the camera
id         card id (default "auto"; reuse to update)
icon       bolt|bell|check|home|car|timer|star
accent     #RRGGBB
style      live|alert|progress
progress   0-100
countdown  seconds to count down
countup    true = running timer
seconds    remove after N seconds
peek       true = pop the island open
tap_url    link opened on tap
button1..3 / button1_url..3_url
reply      label of a reply button (text comes back in EVENT)""", 12f, mono = true)
        t("Remove: action …automation.REMOVE with key and id.", 13f, alpha = 0.8f)
        t("adb example", 16f, bold = true)
        val example = "adb shell am broadcast -n com.ajit.dynamicisland.module.automation/.CommandReceiver -a com.ajit.dynamicisland.automation.SHOW --es key ${Store.key(this)} --es title \"Washing machine\" --es text \"Done in 12 min\" --ei countdown 720 --es icon home --ez peek true"
        t(example, 12f, mono = true)
        btn("Copy adb example") { copy(example) }
        t("Tasker: Action → System → Send Intent. Action, Package and Class as above; Extras as key:value lines; Target: Broadcast Receiver.\nEvents back from the island: Event → System → Intent Received, action com.ajit.dynamicisland.automation.TOOL or …EVENT.", 13f, alpha = 0.8f)
        setContentView(scroll)
    }

    private fun copy(s: String) { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("automation", s)); toast("Copied") }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
