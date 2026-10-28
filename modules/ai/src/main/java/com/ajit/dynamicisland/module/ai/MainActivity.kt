package com.ajit.dynamicisland.module.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.nano.Nano
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.UUID

/** Pick, download and try the on-device model. */
class MainActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var download: MaterialButton
    private lateinit var cancelDl: MaterialButton
    private lateinit var delete: MaterialButton
    private lateinit var group: RadioGroup
    private lateinit var usedBy: TextView
    private lateinit var output: TextView
    private lateinit var runBtn: MaterialButton
    private var testId: String? = null
    private lateinit var nanoInfo: TextView
    private lateinit var nanoBtn: MaterialButton
    private lateinit var lastInput: EditText
    private var nanoJob: kotlinx.coroutines.Job? = null
    private val onChange: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        fun t(s: CharSequence, sp: Float, bold: Boolean = false, alpha: Float = 1f, parent: LinearLayout = c) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); this.alpha = alpha; setPadding(0, dp(6), 0, dp(6))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }.also { parent.addView(it) }

        t("AI Engine", 28f, bold = true)
        t("A small language model that runs on this phone. Meeting Notes uses it to write summaries, and Dynamic Island uses it for Smart Inbox (if you turn that on). " +
            "Text you give it is processed on the device and never sent anywhere. The internet is only used to download a model.", 14f, alpha = 0.75f)
        status = t("", 15f, bold = true)

        t("Model", 18f, bold = true)
        val ram = Models.totalRamGb(this)
        t("This phone has about ${"%.1f".format(ram)} GB of memory.", 13f, alpha = 0.7f)
        group = RadioGroup(this)
        Model.entries.forEach { m ->
            group.addView(RadioButton(this).apply {
                id = 200 + m.ordinal
                val warn = if (!Models.enoughRam(this@MainActivity, m)) " May be too big for this phone." else ""
                text = "${m.label} · ${Formatter.formatShortFileSize(this@MainActivity, m.bytes)}\n${m.blurb}$warn"
                setPadding(dp(4), dp(6), 0, dp(6))
            })
        }
        group.check(200 + Models.selected(this).ordinal)
        group.setOnCheckedChangeListener { _, id -> Models.select(this, Model.entries[id - 200]); LlmEngine.releaseAsync(); refresh() }
        c.addView(group)
        info = t("", 14f, alpha = 0.8f)
        progress = LinearProgressIndicator(this).apply { max = 1000; visibility = View.GONE }
        c.addView(progress)
        download = MaterialButton(this).apply { text = "Download"; setOnClickListener { startDownload() } }
        c.addView(download)
        cancelDl = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Cancel download"; setOnClickListener { DownloadService.cancel() } }
        c.addView(cancelDl)
        delete = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete this model"; setOnClickListener { LlmEngine.releaseAsync(); Models.delete(this@MainActivity, Models.selected(this@MainActivity)); refresh() }
        }
        c.addView(delete)
        c.addView(MaterialSwitch(this).apply {
            text = "Use the GPU (faster on some phones; falls back to the CPU if it fails)"
            isChecked = Models.useGpu(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Models.setUseGpu(this@MainActivity, on); LlmEngine.releaseAsync() }
        })
        t("Models: Qwen3 by Alibaba Cloud and Gemma 4 by Google, both Apache 2.0, converted by Google's LiteRT community. Runtime: Google LiteRT-LM.", 12f, alpha = 0.6f)

        t("Try it", 18f, bold = true)
        val input = EditText(this).apply { hint = "Ask something, e.g. \"Summarise: …\""; minLines = 2 }
        lastInput = input
        c.addView(input)
        runBtn = MaterialButton(this).apply {
            text = "Run"
            setOnClickListener {
                testId?.let { LlmEngine.cancel(it); return@setOnClickListener }
                val q = input.text.toString().trim().ifEmpty { "In two sentences, what can a small on-device language model do well?" }
                runTest(q)
            }
        }
        c.addView(runBtn)
        output = t("", 15f).apply { setTextIsSelectable(true) }

        // ---- Gemini Nano ----
        t("Gemini Nano (built into some phones)", 18f, bold = true)
        nanoInfo = t("Checking whether this phone has Gemini Nano…", 14f, alpha = 0.8f)
        nanoBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { visibility = View.GONE }
        c.addView(nanoBtn)
        checkNano()

        t("Used by", 18f, bold = true)
        usedBy = t("", 14f, alpha = 0.8f)
        c.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open Dynamic Island → Modules"; setOnClickListener { HostVerifier.openHostModules(this@MainActivity) }
        })
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); DownloadService.listeners += onChange; refresh() }
    override fun onPause() { DownloadService.listeners -= onChange; super.onPause() }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        DownloadService.start(this, Models.selected(this))
        download.postDelayed({ refresh() }, 300)
    }

    private fun runTest(q: String) {
        val id = UUID.randomUUID().toString(); testId = id
        runBtn.text = "Stop"; output.text = "Loading the model… (the first run takes a few seconds)"
        LlmEngine.submit(this, id, "You are a helpful, concise assistant.", q, 256, 0.6f, object : LlmEngine.Listener {
            override fun onPartial(text: String) = runOnUiThread { output.text = text }
            override fun onDone(text: String) = runOnUiThread { output.text = text + "\n\n" + statsLine(); finishTest() }
            override fun onError(code: Int, message: String) = runOnUiThread { output.text = "⚠ $message"; finishTest() }
        })
    }

    private fun checkNano() {
        Thread {
            val st = Nano.status()
            runOnUiThread { showNano(st) }
        }.start()
    }

    private fun showNano(st: Nano.Status) {
        nanoBtn.visibility = View.GONE
        when (st) {
            Nano.Status.AVAILABLE -> {
                nanoInfo.text = "✓ Gemini Nano is ready on this phone, with nothing to download. Android only lets an app use it while that app is on screen, " +
                    "so Meeting Notes uses it for questions and notes while you have it open (turn it on there), and this engine's model for everything in the background. " +
                    "Google's ML Kit may send anonymous usage statistics (not your text) when Nano is used."
                nanoBtn.text = "Test Gemini Nano with the text above"; nanoBtn.visibility = View.VISIBLE
                nanoBtn.setOnClickListener { testNano() }
            }
            Nano.Status.DOWNLOADABLE, Nano.Status.DOWNLOADING -> {
                nanoInfo.text = "This phone supports Gemini Nano. Android downloads and updates it for all apps (it doesn't use this app's storage)."
                nanoBtn.text = if (st == Nano.Status.DOWNLOADING) "Downloading… (tap to check)" else "Ask Android to download Gemini Nano"
                nanoBtn.visibility = View.VISIBLE
                nanoBtn.setOnClickListener {
                    if (st == Nano.Status.DOWNLOADING) { checkNano(); return@setOnClickListener }
                    nanoBtn.isEnabled = false
                    nanoJob = Nano.download(
                        onProgress = { b -> runOnUiThread { nanoInfo.text = "Downloading Gemini Nano… ${Formatter.formatShortFileSize(this, b)}" } },
                        onDone = { err -> runOnUiThread { nanoBtn.isEnabled = true; if (err != null) nanoInfo.text = "⚠ $err (it can take a while after setting up a phone; try again later)" else checkNano() } },
                    )
                }
            }
            Nano.Status.ERROR -> nanoInfo.text = "Couldn't check Gemini Nano right now (Android's AICore didn't answer)."
            Nano.Status.UNAVAILABLE -> nanoInfo.text = "This phone doesn't have Gemini Nano (Google supports it on some Pixel, Samsung, Xiaomi, POCO and other recent phones). The models above work on any phone."
        }
    }

    private fun testNano() {
        val q = lastInput.text.toString().trim().ifEmpty { "In two sentences, what can a small on-device language model do well?" }
        output.text = "Asking Gemini Nano…"
        Thread {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = Nano.generateBlocking("You are a helpful, concise assistant.", q, 256, 0.6f) { p -> runOnUiThread { output.text = p } }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            runOnUiThread { output.text = r.text?.let { "$it\n\nGemini Nano · ${ms / 1000.0}s" } ?: "⚠ ${r.message}" }
        }.start()
    }

    override fun onDestroy() { nanoJob?.cancel(); super.onDestroy() }

    private fun statsLine(): String {
        val s = LlmEngine.lastStats ?: return ""
        val gen = (s.totalMs - s.loadMs - s.firstTokenMs.coerceAtLeast(0)).coerceAtLeast(1)
        return "${s.model} on ${s.backend} · load ${s.loadMs / 1000.0}s · first words ${s.firstTokenMs / 1000.0}s · about ${"%.0f".format(s.chars / 3.5 / (gen / 1000.0))} tokens/s"
    }

    private fun finishTest() { testId = null; runBtn.text = "Run" }

    private fun refresh() {
        val m = Models.selected(this)
        val dl = DownloadService.current
        val present = Models.isPresent(this, m)
        val connected = IslandModuleService.current?.island?.isConnected == true
        status.text = when {
            Models.active(this) == null -> "⚠ No model yet: pick one and tap Download"
            !HostVerifier.isHostInstalled(this) -> "✓ Ready (Dynamic Island isn't installed)"
            !connected -> "✓ Ready · approve this module in Dynamic Island → Modules"
            else -> "✓ Ready · ${Models.active(this)!!.label} · connected to the island"
        }
        info.text = when {
            dl != null -> "${DownloadService.phase} ${dl.label}: ${Formatter.formatShortFileSize(this, DownloadService.done)} of ${Formatter.formatShortFileSize(this, dl.bytes)}"
            DownloadService.lastError != null -> "⚠ ${DownloadService.lastError}"
            present -> "✓ ${m.label} is on this phone and works offline."
            Models.partFile(this, m).exists() -> "Partly downloaded. Tap Download to resume. Wi-Fi recommended."
            else -> "${m.label} isn't downloaded. Wi-Fi recommended. Free storage: ${Formatter.formatShortFileSize(this, Models.freeBytes(this))}."
        }
        progress.visibility = if (dl != null) View.VISIBLE else View.GONE
        if (dl != null) progress.progress = (DownloadService.done * 1000 / dl.bytes).toInt()
        download.visibility = if (!present && dl == null) View.VISIBLE else View.GONE
        cancelDl.visibility = if (dl != null) View.VISIBLE else View.GONE
        delete.visibility = if (present && dl == null) View.VISIBLE else View.GONE
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = dl == null
        runBtn.isEnabled = Models.active(this) != null
        val pm = packageManager
        usedBy.text = Usage.all(this).entries.sortedByDescending { it.value }.joinToString("\n") { (pkg, at) ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "• $name · ${DateUtils.getRelativeTimeSpanString(at)}"
        }.ifEmpty { "No app has used it yet." }
    }
}
