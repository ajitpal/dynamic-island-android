package com.ajit.dynamicisland.module.speech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.speech.hig.Hig
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.speech.SpeechContract
import java.util.UUID

/** Speech Pack (HIG layout): which model, download it, try it on an audio file, who uses it. */
class MainActivity : AppCompatActivity() {
    private lateinit var hig: Hig
    private lateinit var page: Hig.Page
    private lateinit var top: LinearLayout
    private lateinit var models: LinearLayout
    private lateinit var tryBox: LinearLayout
    private lateinit var rest: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { runOnUiThread { drawTop(); drawModels() } }

    // Try It state
    private var jobId: String? = null
    private var jobStage: String? = null
    private var jobFraction = 0f
    private var jobStarted = 0L
    private val lines = mutableListOf<Pair<Long, String>>()
    private var jobResult: String? = null
    private val tick = object : Runnable { override fun run() { if (jobId != null) { drawTry(); main.postDelayed(this, 1000) } } }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { tryFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        page = hig.page("Speech Pack", "Hindi and Hinglish speech-to-text on this phone, for Meeting Notes. Audio and text never leave the phone; the internet is only used to download a model.")
        fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { page.column.addView(it, hig.mp()) }
        top = box(); models = box(); tryBox = box(); rest = box()
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        drawAll()
    }

    override fun onResume() { super.onResume(); DownloadService.listeners += onChange; drawAll() }
    override fun onPause() { DownloadService.listeners -= onChange; super.onPause() }
    override fun onDestroy() { main.removeCallbacks(tick); super.onDestroy() }

    private fun drawAll() { drawTop(); drawModels(); drawTry(); drawRest() }

    private fun drawTop() {
        top.removeAllViews()
        val active = Models.active(this)
        val connected = IslandModuleService.current?.island?.isConnected == true
        val (title, sub, color) = when {
            DownloadService.current != null -> Triple("Downloading the ${DownloadService.current!!.label} Model", "You can leave this screen; it continues in the background.", 0xFF007AFF.toInt())
            active == null -> Triple("No Model Yet", "Pick a model below and download it once. After that, Hindi works offline.", 0xFFFF9500.toInt())
            !connected -> Triple("Ready · ${active.label}", "Meeting Notes uses it automatically for Hindi and Hinglish.", 0xFF34C759.toInt())
            else -> Triple("Ready · ${active.label}", "Connected to Dynamic Island. Meeting Notes uses it for Hindi and Hinglish.", 0xFF34C759.toInt())
        }
        hig.section(top).add(hig.row(title, sub, hig.tile(if (active != null) R.drawable.ic_check else R.drawable.ic_wave, color, 30)))
    }

    private fun drawModels() {
        models.removeAllViews()
        val dl = DownloadService.current
        val sel = Models.selected(this)
        val ram = Models.totalRamGb(this); val free = runCatching { Models.freeBytes(this) }.getOrDefault(0L)
        val phone = listOfNotNull(
            if (ram > 0) "This phone has about ${"%.1f".format(ram)} GB of memory" else null,
            if (free > 0) "${Formatter.formatShortFileSize(this, free)} of storage free" else null,
        ).joinToString("; ").let { if (it.isEmpty()) "" else " $it." }
        val s = hig.section(models, "Model", "Meta's Omnilingual ASR, tuned here to write Hindi in Devanagari and English words in Latin letters.$phone")
        Model.entries.forEach { m ->
            val present = Models.isPresent(this, m)
            val warn = if (!Models.enoughRam(this, m)) " May be too big for this phone." else ""
            val check = if (m == sel) TextView(this).apply { text = "✓"; textSize = 20f; setTextColor(hig.blue); contentDescription = "Selected" } else null
            s.add(hig.row(m.label, "${Formatter.formatShortFileSize(this, m.bytes)} · ${if (present) "Downloaded" else "Not downloaded"} · ${m.blurb}$warn",
                hig.tile(R.drawable.ic_wave, if (present) 0xFFFF9500.toInt() else 0xFF8E8E93.toInt(), 30), trailing = check,
                onClick = if (dl == null && m != sel) ({ Models.select(this, m); Transcriber.releaseAsync(); SpeechModule.refreshTile(); drawAll() }) else null))
        }
        val a = hig.section(models, footer = when {
            dl != null -> null
            DownloadService.lastError != null -> "⚠ ${DownloadService.lastError}"
            Models.isPresent(this, sel) -> "The ${sel.label} model is on this phone and works offline."
            Models.partFile(this, sel).exists() -> "Partly downloaded. Download resumes where it stopped. Wi-Fi recommended."
            else -> "Downloads once from Hugging Face (checked against a fixed checksum). Wi-Fi recommended."
        })
        when {
            dl != null -> {
                val pct = (DownloadService.done * 100 / dl.bytes).toInt()
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(hig.row("${DownloadService.phase} the ${dl.label} model", "${Formatter.formatShortFileSize(this, DownloadService.done)} of ${Formatter.formatShortFileSize(this, dl.bytes)} · $pct%"), hig.mp())
                col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = pct; progressTintList = android.content.res.ColorStateList.valueOf(hig.color(R.color.hig_blue_fill))
                }, hig.mp().apply { marginStart = hig.dp(16); marginEnd = hig.dp(16); bottomMargin = hig.dp(10) })
                a.add(col)
                a.add(hig.action("Cancel Download", destructive = true) { DownloadService.cancel() })
            }
            !Models.isPresent(this, sel) -> a.add(hig.action("Download the ${sel.label} Model") { startDownload() })
            else -> a.add(hig.action("Delete the ${sel.label} Model", destructive = true) { Transcriber.releaseAsync(); Models.delete(this, sel); SpeechModule.refreshTile(); drawAll() })
        }
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        DownloadService.start(this, Models.selected(this))
        main.postDelayed({ drawAll() }, 300)
    }

    // ---- Try It: transcribe an audio file here ----

    private fun drawTry() {
        tryBox.removeAllViews()
        val s = hig.section(tryBox, "Try It", if (Models.active(this) == null && jobId == null) "Download a model above first, then pick a recording to try it."
            else "Pick a recording (voice note, call recording, meeting audio). The transcript appears as it's written.")
        val running = jobId != null
        jobStage?.let { st ->
            val secs = ((SystemClock.elapsedRealtime() - jobStarted) / 1000).toInt()
            val lead: View = if (running) ProgressBar(this).apply { isIndeterminate = true }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
                else hig.tile(R.drawable.ic_check, 0xFF34C759.toInt(), 22)
            s.add(hig.row(st, if (running) "${(jobFraction * 100).toInt()}% · $secs s" else jobResult, lead).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        }
        lines.takeLast(30).forEach { (ms, text) ->
            s.add(hig.row(text, DateUtils.formatElapsedTime(ms / 1000)))
        }
        val enabled = running || Models.active(this) != null
        val btn = hig.primary(if (running) "Stop" else "Choose Audio File") {
            if (running) jobId?.let { Transcriber.cancel(it) } else pick.launch(arrayOf("audio/*"))
        }.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.5f }
        tryBox.addView(btn, hig.mp().apply { topMargin = hig.dp(12); marginStart = hig.dp(16); marginEnd = hig.dp(16) })
    }

    private fun tryFile(uri: Uri) {
        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return
        val id = UUID.randomUUID().toString()
        jobId = id; jobStage = "Starting"; jobFraction = 0f; jobStarted = SystemClock.elapsedRealtime(); lines.clear(); jobResult = null
        main.post(tick)
        Transcriber.submit(this, id, pfd, object : Transcriber.Listener {
            override fun line(startMs: Long, endMs: Long, text: String) = runOnUiThread { lines += startMs to text; drawTry() }
            override fun progress(stage: String, fraction: Float) = runOnUiThread {
                jobStage = if (stage == SpeechContract.STAGE_LOADING) "Loading the model" else "Transcribing"; jobFraction = fraction; drawTry()
            }
            override fun done() = runOnUiThread { jobId = null; jobStage = "Done"; jobResult = Transcriber.lastStats; drawTry() }
            override fun error(code: Int, message: String) = runOnUiThread { jobId = null; jobStage = if (code == SpeechContract.E_CANCELLED) "Stopped" else "Couldn't Transcribe"; jobResult = message; drawTry() }
        })
        drawTry()
    }

    // ---- used by, credits ----

    private fun drawRest() {
        rest.removeAllViews()
        val used = hig.section(rest, "Used By")
        val pm = packageManager
        val list = Usage.all(this).entries.sortedByDescending { it.value }
        if (list.isEmpty()) used.add(hig.row("No app has used it yet", titleColor = hig.secondary))
        list.forEach { (pkg, at) ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            used.add(hig.row(name, value = DateUtils.getRelativeTimeSpanString(at).toString()))
        }
        used.add(hig.action("Open Dynamic Island → Modules") { HostVerifier.openHostModules(this) })
        hig.note(rest, "Speech model: Omnilingual ASR by Meta (Apache 2.0), ONNX export by k2-fsa. Voice detection: Silero VAD (MIT). Runtime: ONNX Runtime by Microsoft (MIT).")
    }
}
