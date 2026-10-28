package com.ajit.dynamicisland.module.ai

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.ai.hig.Hig
import com.ajit.dynamicisland.nano.Nano
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.ai.AiContract
import java.util.UUID

/**
 * AI Engine (HIG layout): which model, download it, try it with live progress, and Diagnostics that
 * explain what happened on this phone (test run, report to copy, clear prepared files, restart).
 */
class MainActivity : AppCompatActivity() {
    companion object { const val EXTRA_FOCUS_TRY = "focusTry" }

    private lateinit var hig: Hig
    private lateinit var page: Hig.Page
    private lateinit var top: LinearLayout
    private lateinit var models: LinearLayout
    private lateinit var tryBox: LinearLayout
    private lateinit var diag: LinearLayout
    private lateinit var rest: LinearLayout
    private lateinit var input: EditText
    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { runOnUiThread { drawTop(); drawModels() } }

    /** The Try It run in progress. */
    private var testId: String? = null
    private var stageText: String? = null
    private var stageAt = 0L
    private var output: String = ""
    private val tick = object : Runnable { override fun run() { if (testId != null) { drawTry(); main.postDelayed(this, 1000) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        page = hig.page("AI Engine", "A language model that runs on this phone. What you give it never leaves the phone; the internet is only used to download a model.")
        fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { page.column.addView(it, hig.mp()) }
        top = box(); models = box(); tryBox = box(); diag = box(); rest = box()
        input = EditText(this).apply {
            hint = "Ask something, or paste text and ask for a summary"; minLines = 2; background = null
            setPadding(hig.dp(16), hig.dp(12), hig.dp(16), hig.dp(12)); textSize = 17f
        }
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        drawAll()
        if (intent.getBooleanExtra(EXTRA_FOCUS_TRY, false) || intent.getStringExtra(com.ajit.dynamicisland.sdk.IslandContract.EXTRA_ARG) == AiModule.ARG_TRY)
            page.scroll.post { page.scroll.smoothScrollTo(0, tryBox.top); input.requestFocus() }
    }

    override fun onResume() { super.onResume(); DownloadService.listeners += onChange; drawAll() }
    override fun onPause() { DownloadService.listeners -= onChange; super.onPause() }
    override fun onDestroy() { main.removeCallbacks(tick); super.onDestroy() }

    private fun drawAll() { drawTop(); drawModels(); drawTry(); drawDiag(); drawRest() }

    // ------------------------------------------------------------------ status

    private fun drawTop() {
        top.removeAllViews()
        val s = hig.section(top)
        val active = Models.active(this)
        val connected = IslandModuleService.current?.island?.isConnected == true
        val (title, sub, color) = when {
            DownloadService.current != null -> Triple("Downloading ${DownloadService.current!!.label}", "You can leave this screen; it continues in the background.", 0xFF007AFF.toInt())
            active == null -> Triple("No Model Yet", "Pick a model below and download it once. After that, everything works offline.", 0xFFFF9500.toInt())
            !HostVerifier.isHostInstalled(this) -> Triple("Ready · ${active.label}", "Dynamic Island isn't installed.", 0xFF34C759.toInt())
            !connected -> Triple("Ready · ${active.label}", "Turn on AI Engine in Dynamic Island → Modules to use it there.", 0xFF34C759.toInt())
            else -> Triple("Ready · ${active.label}", "Connected to Dynamic Island.", 0xFF34C759.toInt())
        }
        s.add(hig.row(title, sub, hig.tile(if (active != null) R.drawable.ic_check else R.drawable.ic_spark, color, 30)))
    }

    // ------------------------------------------------------------------ models

    private fun drawModels() {
        models.removeAllViews()
        val dl = DownloadService.current
        val sel = Models.selected(this)
        val s = hig.section(models, "Model", "This phone has about ${"%.1f".format(Models.totalRamGb(this))} GB of memory. Free storage: ${Formatter.formatShortFileSize(this, Models.freeBytes(this))}.")
        Model.entries.forEach { m ->
            val present = Models.isPresent(this, m)
            val warn = if (!Models.enoughRam(this, m)) " May be too big for this phone." else ""
            val crashes = Diag.crashes(this, m)
            val note = if (crashes > 0) " Closed while loading ${if (crashes == 1) "once" else "$crashes times"} on this phone." else ""
            val check = if (m == sel) TextView(this).apply { text = "✓"; textSize = 20f; setTextColor(hig.blue); contentDescription = "Selected" } else null
            s.add(hig.row(m.label, "${Formatter.formatShortFileSize(this, m.bytes)} · ${if (present) "Downloaded" else "Not downloaded"} · ${m.blurb}$warn$note",
                hig.tile(R.drawable.ic_spark, if (present) 0xFF5856D6.toInt() else 0xFF8E8E93.toInt(), 30), trailing = check,
                onClick = if (dl == null && m != sel) ({ Models.select(this, m); LlmEngine.releaseAsync(); AiModule.refreshTile(); drawAll() }) else null))
        }
        val a = hig.section(models, footer = when {
            dl != null -> null
            DownloadService.lastError != null -> "⚠ ${DownloadService.lastError}"
            Models.isPresent(this, sel) -> "${sel.label} is on this phone and works offline."
            Models.partFile(this, sel).exists() -> "Partly downloaded. Download resumes where it stopped. Wi-Fi recommended."
            else -> "Downloads once from Hugging Face (checked against a fixed checksum). Wi-Fi recommended."
        })
        when {
            dl != null -> {
                val pct = (DownloadService.done * 100 / dl.bytes).toInt()
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(hig.row("${DownloadService.phase} ${dl.label}", "${Formatter.formatShortFileSize(this, DownloadService.done)} of ${Formatter.formatShortFileSize(this, dl.bytes)} · $pct%"), hig.mp())
                col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = pct; progressTintList = android.content.res.ColorStateList.valueOf(hig.color(R.color.hig_blue_fill))
                }, hig.mp().apply { marginStart = hig.dp(16); marginEnd = hig.dp(16); bottomMargin = hig.dp(10) })
                a.add(col)
                a.add(hig.action("Cancel Download", destructive = true) { DownloadService.cancel() })
            }
            !Models.isPresent(this, sel) -> a.add(hig.action("Download ${sel.label}") { startDownload() })
            else -> a.add(hig.action("Delete ${sel.label}", destructive = true) {
                LlmEngine.releaseAsync(); Models.delete(this, sel); AiModule.refreshTile(); drawAll()
            })
        }
        hig.section(models, footer = "The GPU is faster on some phones. If it fails, AI Engine switches back to the CPU by itself.")
            .add(hig.toggle("Use GPU", Models.useGpu(this)) { on -> Models.setUseGpu(this, on); LlmEngine.releaseAsync() })
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        DownloadService.start(this, Models.selected(this))
        main.postDelayed({ drawAll() }, 300)
    }

    // ------------------------------------------------------------------ try it

    private fun drawTry() {
        // Keep the text field (and what's typed) when redrawing.
        (input.parent as? android.view.ViewGroup)?.removeView(input)
        tryBox.removeAllViews()
        val s = hig.section(tryBox, "Try It")
        s.add(input)
        val running = testId != null
        stageText?.let { st ->
            val secs = ((SystemClock.elapsedRealtime() - stageAt) / 1000).toInt()
            val lead: View = if (running) ProgressBar(this).apply { isIndeterminate = true }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
                else hig.tile(R.drawable.ic_check, 0xFF34C759.toInt(), 22)
            s.add(hig.row(st, if (running) "$secs s" else null, lead).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        }
        if (output.isNotBlank()) s.add(hig.text(output, Hig.Style.BODY).apply { setPadding(hig.dp(16), hig.dp(12), hig.dp(16), hig.dp(12)); setTextIsSelectable(!running) })
        val btn = hig.primary(if (running) "Stop" else "Run") {
            if (running) { testId?.let { LlmEngine.cancel(it) } }
            else run(input.text.toString().trim().ifEmpty { "In two sentences, what can a small on-device language model do well?" }, 256)
        }.apply { isEnabled = running || Models.active(this@MainActivity) != null; alpha = if (isEnabled) 1f else 0.5f }
        tryBox.addView(btn, hig.mp().apply { topMargin = hig.dp(12); marginStart = hig.dp(16); marginEnd = hig.dp(16) })
    }

    /** Run a prompt with live stages. Used by Try It and by Diagnostics → Test AI Engine. */
    private fun run(q: String, maxTokens: Int, onFinished: (Boolean) -> Unit = {}) {
        if (testId != null) return
        val id = UUID.randomUUID().toString(); testId = id
        output = ""; setStage("Starting")
        main.post(tick)
        LlmEngine.submit(this, id, "You are a helpful, concise assistant.", q, maxTokens, 0.6f, object : LlmEngine.Listener {
            override fun onProgress(p: Bundle) = runOnUiThread {
                setStage(when (p.getString(AiContract.P_STAGE)) {
                    AiContract.STAGE_QUEUED -> "Waiting for ${p.getString(AiContract.P_BUSY_WITH) ?: "another request"}"
                    AiContract.STAGE_LOADING -> "Loading ${p.getString(AiContract.P_MODEL)}"
                    AiContract.STAGE_WRITING -> "Writing"
                    else -> "Reading your text"
                }); drawTry()
            }
            override fun onPartial(text: String) = runOnUiThread { output = text; drawTry() }
            override fun onDone(text: String) = runOnUiThread { output = text; finish(statsLine()); onFinished(true) }
            override fun onError(code: Int, message: String) = runOnUiThread { output = "⚠ $message"; finish(if (code == AiContract.E_CANCELLED) "Stopped" else "Didn't finish"); onFinished(false) }
        })
        drawTry()
    }

    private fun setStage(s: String) { stageText = s; stageAt = SystemClock.elapsedRealtime() }
    private fun finish(summary: String) { testId = null; stageText = summary; drawTry(); drawDiag() }

    private fun statsLine(): String {
        val s = LlmEngine.lastStats ?: return "Done"
        return "${s.model} · ${s.backend}" + (if (s.loadMs > 0) " · loaded in ${"%.1f".format(s.loadMs / 1000.0)} s" else "") +
            " · first word ${"%.1f".format(s.firstTokenMs / 1000.0)} s" + (if (s.writeTps > 0) " · about ${"%.0f".format(s.writeTps)} words/s" else "")
    }

    // ------------------------------------------------------------------ diagnostics

    private fun drawDiag() {
        diag.removeAllViews()
        val runs = Diag.runs(this)
        val s = hig.section(diag, "Diagnostics", "If AI doesn't answer, tap Test AI Engine, then Copy Report and send it to the developer. The report stays on your phone unless you share it.")
        s.add(hig.action("Test AI Engine") {
            page.scroll.smoothScrollTo(0, tryBox.top)
            run("Reply with one short sentence: what is two plus two?", 32)
        })
        s.add(hig.action("Copy Report") {
            Thread {
                val r = Diag.report(this)
                runOnUiThread {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI Engine report", r))
                    Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
                }
            }.start()
        })
        s.add(hig.action("Clear Prepared Models") {
            LlmEngine.clearPrepared(this) { runOnUiThread { Toast.makeText(this, "Cleared. The next answer takes longer while the model is prepared again.", Toast.LENGTH_LONG).show(); drawDiag() } }
        })
        s.add(hig.action("Restart AI Engine", destructive = true) {
            Diag.log("restart requested from the screen")
            android.os.Process.killProcess(android.os.Process.myPid())
        })
        if (runs.isNotEmpty()) {
            val r = hig.section(diag, "Recent Runs")
            runs.take(5).forEach { line -> r.add(hig.text(line, Hig.Style.FOOTNOTE, hig.secondary).apply { setPadding(hig.dp(16), hig.dp(10), hig.dp(16), hig.dp(10)) }) }
        }
    }

    // ------------------------------------------------------------------ Gemini Nano, used by, credits

    private var nanoJob: kotlinx.coroutines.Job? = null

    private fun drawRest() {
        rest.removeAllViews()
        val nano = hig.section(rest, "Gemini Nano")
        val nanoRow = hig.row("Checking whether this phone has Gemini Nano…", null, hig.tile(R.drawable.ic_spark, 0xFF8E8E93.toInt(), 30))
        nano.add(nanoRow)
        Thread { val st = Nano.status(); runOnUiThread { showNano(nano, nanoRow, st) } }.start()

        val used = hig.section(rest, "Used By")
        val pm = packageManager
        val list = Usage.all(this).entries.sortedByDescending { it.value }
        if (list.isEmpty()) used.add(hig.row("No app has used it yet", titleColor = hig.secondary))
        list.forEach { (pkg, at) ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            used.add(hig.row(name, value = DateUtils.getRelativeTimeSpanString(at).toString()))
        }
        used.add(hig.action("Open Dynamic Island → Modules") { HostVerifier.openHostModules(this) })
        hig.note(rest, "Models: Qwen3 by Alibaba Cloud and Gemma 4 by Google (Apache 2.0), converted by Google's LiteRT community. Runtime: Google LiteRT-LM.")
    }

    private fun showNano(s: Hig.Section, row: View, st: Nano.Status) {
        s.clear()
        when (st) {
            Nano.Status.AVAILABLE -> {
                s.add(hig.row("Gemini Nano Is Ready", "Built into this phone, nothing to download. Android lets an app use it only while that app is on screen, so Meeting Notes uses it while open (turn it on there). Google's ML Kit may send anonymous usage statistics (not your text).",
                    hig.tile(R.drawable.ic_check, 0xFF34C759.toInt(), 30)))
                s.add(hig.action("Test Gemini Nano") { testNano() })
            }
            Nano.Status.DOWNLOADABLE, Nano.Status.DOWNLOADING -> {
                s.add(hig.row("This Phone Supports Gemini Nano", "Android downloads and updates it for all apps (it doesn't use this app's storage).", hig.tile(R.drawable.ic_spark, 0xFF5856D6.toInt(), 30)))
                s.add(hig.action(if (st == Nano.Status.DOWNLOADING) "Downloading… Tap to Check" else "Ask Android to Download It") {
                    if (st == Nano.Status.DOWNLOADING) { drawRest(); return@action }
                    nanoJob = Nano.download(
                        onProgress = { b -> runOnUiThread { Toast.makeText(this, "Downloading Gemini Nano… ${Formatter.formatShortFileSize(this, b)}", Toast.LENGTH_SHORT).show() } },
                        onDone = { err -> runOnUiThread { if (err != null) Toast.makeText(this, "$err (try again later)", Toast.LENGTH_LONG).show(); drawRest() } },
                    )
                })
            }
            Nano.Status.ERROR -> s.add(hig.row("Couldn't Check Gemini Nano", "Android's AICore didn't answer right now.", hig.tile(R.drawable.ic_info, 0xFF8E8E93.toInt(), 30)))
            Nano.Status.UNAVAILABLE -> s.add(hig.row("Not on This Phone", "Google supports Gemini Nano on some recent Pixel, Samsung, Xiaomi, POCO and other phones. The models above work on any phone.", hig.tile(R.drawable.ic_info, 0xFF8E8E93.toInt(), 30)))
        }
    }

    private fun testNano() {
        val q = input.text.toString().trim().ifEmpty { "In two sentences, what can a small on-device language model do well?" }
        output = ""; setStage("Asking Gemini Nano"); testId = "nano"; main.post(tick); drawTry()
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val r = Nano.generateBlocking("You are a helpful, concise assistant.", q, 256, 0.6f) { p -> runOnUiThread { output = p; drawTry() } }
            val ms = SystemClock.elapsedRealtime() - t0
            runOnUiThread { output = r.text ?: "⚠ ${r.message}"; finish("Gemini Nano · ${"%.1f".format(ms / 1000.0)} s") }
        }.start()
    }
}
