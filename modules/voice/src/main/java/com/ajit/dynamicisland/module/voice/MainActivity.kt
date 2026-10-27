package com.ajit.dynamicisland.module.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Settings, model download and the list of saved meetings. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var modelInfo: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var downloadBtn: MaterialButton
    private lateinit var deleteBtn: MaterialButton
    private lateinit var recordBtn: MaterialButton
    private lateinit var live: TextView
    private lateinit var list: LinearLayout
    private val onChange: () -> Unit = { refreshRecording() }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }

        text(c, "Meeting Notes", 28f, bold = true)
        text(c, "Record a meeting from the island and get a live English transcript, made on this phone. Audio and transcripts never leave it. " +
            "Only record people who know they're being recorded: the law in many places requires everyone's consent.", 14f, alpha = 0.75f)
        status = text(c, "", 15f, bold = true)

        // ---- model ----
        text(c, "Speech model (English)", 18f, bold = true)
        val group = RadioGroup(this)
        ModelStore.Size.entries.forEach { s ->
            group.addView(RadioButton(this).apply {
                id = 100 + s.ordinal; text = "${s.label}: ${s.blurb}"; isChecked = ModelStore.selected(this@MainActivity) == s
            })
        }
        group.setOnCheckedChangeListener { _, idd -> ModelStore.select(this, ModelStore.Size.entries[idd - 100]); refresh() }
        c.addView(group)
        modelInfo = text(c, "", 14f, alpha = 0.8f)
        progress = LinearProgressIndicator(this).apply { max = 1000; visibility = android.view.View.GONE }
        c.addView(progress)
        downloadBtn = MaterialButton(this).apply { text = "Download model"; setOnClickListener { download() } }
        c.addView(downloadBtn)
        deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Delete this model"; setOnClickListener { ModelStore.delete(this@MainActivity, ModelStore.selected(this@MainActivity)); refresh() }
        }
        c.addView(deleteBtn)

        // ---- record ----
        text(c, "Record", 18f, bold = true)
        text(c, "Tip: add \"Record meeting\" to your island tools (Dynamic Island → Modules → Meeting Notes, then Island tools). Long-press the empty island to start.", 14f, alpha = 0.75f)
        recordBtn = MaterialButton(this).apply { setOnClickListener { toggleRecording() } }
        c.addView(recordBtn)
        live = text(c, "", 14f, alpha = 0.85f)
        c.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open Dynamic Island → Modules"; setOnClickListener { HostVerifier.openHostModules(this@MainActivity) }
        })

        // ---- recordings ----
        text(c, "Saved meetings", 18f, bold = true)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(list)
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); RecorderService.listeners += onChange; refresh() }
    override fun onPause() { RecorderService.listeners -= onChange; super.onPause() }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val connected = IslandModuleService.current?.island?.isConnected == true
        status.text = when {
            !HostVerifier.isHostInstalled(this) -> "⚠ Dynamic Island isn't installed (you can still record from here)"
            !has(Manifest.permission.RECORD_AUDIO) -> "⚠ Microphone not allowed yet"
            !connected -> "Approve this module in Dynamic Island → Modules"
            else -> "✓ Connected to the island"
        }
        val s = ModelStore.selected(this)
        val dl = ModelStore.downloading
        val present = ModelStore.isPresent(this, s)
        modelInfo.text = when {
            dl != null -> "Downloading ${dl.label}…"
            present -> "✓ ${s.label} is on this phone (${Formatter.formatShortFileSize(this, ModelStore.sizeOnDisk(this, s))}). Works offline."
            else -> "${s.label} isn't downloaded yet. It's a one-time download from Moonshine AI's servers; Wi-Fi recommended."
        }
        downloadBtn.isEnabled = dl == null
        downloadBtn.visibility = if (present) android.view.View.GONE else android.view.View.VISIBLE
        deleteBtn.visibility = if (present && dl == null && RecorderService.state == RecorderService.State.IDLE) android.view.View.VISIBLE else android.view.View.GONE
        refreshRecording()
        refreshList()
    }

    private fun refreshRecording() {
        val st = RecorderService.state
        recordBtn.text = when (st) {
            RecorderService.State.IDLE -> "● Start recording"
            RecorderService.State.SAVING -> "Saving…"
            else -> "■ Stop and save"
        }
        recordBtn.isEnabled = st != RecorderService.State.SAVING && (st != RecorderService.State.IDLE || ModelStore.isPresent(this))
        val svc = RecorderService.instance
        live.text = when (st) {
            RecorderService.State.IDLE -> if (ModelStore.isPresent(this)) "" else "Download a model first."
            RecorderService.State.STARTING -> "Loading the speech model…"
            RecorderService.State.SAVING -> "Finishing the transcript…"
            else -> "${if (st == RecorderService.State.PAUSED) "Paused" else "Recording"} · ${Notes.clock(svc?.elapsedMs ?: 0)} · ${svc?.lineCount ?: 0} lines\n${svc?.liveCaption ?: ""}"
        }
        if (st == RecorderService.State.IDLE) refreshListIfChanged()
    }

    private var shownCount = -1
    private fun refreshListIfChanged() { if (Recordings.root(this).list()?.size != shownCount) refreshList() }

    private fun refreshList() {
        list.removeAllViews()
        val all = Recordings.all(this)
        shownCount = Recordings.root(this).list()?.size ?: 0
        if (all.isEmpty()) { text(list, "Nothing yet. Your recordings appear here.", 14f, alpha = 0.6f); return }
        all.forEach { r ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                setOnClickListener { startActivity(Intent(this@MainActivity, TranscriptActivity::class.java).putExtra(TranscriptActivity.EXTRA_ID, r.id)) }
            }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
            card.addView(inner)
            text(inner, r.title, 16f, bold = true)
            val items = Notes.actionItems(r.lines).size
            text(inner, DateUtils.getRelativeTimeSpanString(r.startedAt).toString() + " · ${Notes.clock(r.durationMs)} · ${Notes.wordCount(r.lines)} words" +
                if (items > 0) " · $items possible action items" else "", 13f, alpha = 0.7f)
            r.lines.firstOrNull()?.let { text(inner, it.text.take(140), 13f, alpha = 0.85f).maxLines = 2 }
            list.addView(card)
        }
    }

    private fun toggleRecording() {
        when (RecorderService.state) {
            RecorderService.State.IDLE -> {
                val need = neededPermissions().filterNot(::has)
                if (Manifest.permission.RECORD_AUDIO in need) { ask.launch(need.toTypedArray()); return }
                RecorderService.start(this)
            }
            else -> RecorderService.command(this, RecorderService.ACTION_STOP)
        }
        recordBtn.postDelayed({ refreshRecording() }, 300)
    }

    private fun download() {
        val s = ModelStore.selected(this)
        progress.visibility = android.view.View.VISIBLE; progress.isIndeterminate = true
        ModelStore.download(this, s,
            progress = { done, total -> runOnUiThread { progress.isIndeterminate = false; progress.progress = (done * 1000 / total.coerceAtLeast(1)).toInt() } },
            done = { _, err -> runOnUiThread {
                progress.visibility = android.view.View.GONE
                if (err != null) modelInfo.text = "Download failed: ${err.message ?: err.javaClass.simpleName}. Check your connection and try again."
                if (err == null) refresh() else { downloadBtn.isEnabled = true }
            } })
        refresh()
    }
}
