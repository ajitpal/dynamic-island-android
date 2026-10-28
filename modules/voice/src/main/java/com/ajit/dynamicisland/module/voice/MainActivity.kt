package com.ajit.dynamicisland.module.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.ajit.dynamicisland.module.voice.hig.Hig
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.speech.SpeechClient
import com.ajit.dynamicisland.sdk.speech.SpeechContract

/** Meeting Notes (HIG layout): record, language, import, English model, AI notes, saved meetings. */
class MainActivity : AppCompatActivity() {
    private lateinit var hig: Hig
    private lateinit var page: Hig.Page
    private lateinit var top: LinearLayout
    private lateinit var record: LinearLayout
    private lateinit var language: LinearLayout
    private lateinit var english: LinearLayout
    private lateinit var importBox: LinearLayout
    private lateinit var ai: LinearLayout
    private lateinit var saved: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { runOnUiThread { drawTop(); drawRecord(); if (RecorderService.state == RecorderService.State.IDLE) drawSaved() } }

    /** Last checked: can Android / the Speech Pack transcribe Hindi here? */
    private var androidHindi = AndroidHindi.UNKNOWN
    private var speechPackReady = false
    private var androidDownloading = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { drawAll() }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { RecorderService.import(this, it); main.postDelayed({ drawAll() }, 300) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        page = hig.page("Meeting Notes", "Record a meeting or import audio, and get a transcript and notes made on this phone. Only record people who know they're being recorded: the law in many places requires everyone's consent.")
        fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { page.column.addView(it, hig.mp()) }
        top = box(); record = box(); language = box(); importBox = box(); english = box(); ai = box(); saved = box()
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        androidHindi = VoicePrefs.androidHindi(this)
        drawAll()
    }

    override fun onResume() {
        super.onResume(); RecorderService.listeners += onChange; drawAll()
        AndroidStt.check(this) { a -> androidHindi = a; VoicePrefs.setAndroidHindi(this, a); drawLanguage() }
        if (SpeechClient.isTrusted(this)) SpeechClient(this).let { c -> c.status { b -> c.close(); speechPackReady = b?.getBoolean(SpeechContract.S_READY) == true; drawLanguage() } }
        else speechPackReady = false
    }
    override fun onPause() { RecorderService.listeners -= onChange; super.onPause() }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun drawAll() { drawTop(); drawRecord(); drawLanguage(); drawImport(); drawEnglish(); drawAi(); drawSaved() }

    // ------------------------------------------------------------------ status

    private fun drawTop() {
        top.removeAllViews()
        val connected = IslandModuleService.current?.island?.isConnected == true
        val problem = RecorderService.lastProblem
        val (title, sub, color) = when {
            problem != null && RecorderService.state == RecorderService.State.IDLE -> Triple("Couldn't Transcribe", problem, 0xFFFF9500.toInt())
            !has(Manifest.permission.RECORD_AUDIO) -> Triple("Microphone Not Allowed Yet", "Meeting Notes asks when you start your first recording.", 0xFFFF9500.toInt())
            !HostVerifier.isHostInstalled(this) -> Triple("Dynamic Island Isn't Installed", "You can still record from here.", 0xFF8E8E93.toInt())
            !connected -> Triple("Not Connected to the Island", "Turn on Meeting Notes in Dynamic Island → Modules.", 0xFF8E8E93.toInt())
            else -> Triple("Connected to the Island", "Tap Record meeting in your island tools, or start here.", 0xFF34C759.toInt())
        }
        hig.section(top).add(hig.row(title, sub, hig.tile(if (color == 0xFF34C759.toInt()) R.drawable.ic_check else R.drawable.ic_info, color, 30)))
    }

    // ------------------------------------------------------------------ record

    private fun drawRecord() {
        record.removeAllViews()
        val st = RecorderService.state
        val svc = RecorderService.instance
        val s = hig.section(record)
        when (st) {
            RecorderService.State.IDLE -> {}
            RecorderService.State.STARTING -> s.add(progressRow("Getting Ready", if (VoicePrefs.language(this).isHindi) "Preparing Hindi speech recognition" else "Loading the speech model"))
            RecorderService.State.RECORDING, RecorderService.State.PAUSED -> {
                val live = if (svc?.livePlan == Plan.SpeechPackAfter) "Hindi transcript is written after you stop" else (svc?.liveCaption ?: "Listening…")
                s.add(hig.row("${if (st == RecorderService.State.PAUSED) "Paused" else "Recording"} · ${Notes.clock(svc?.elapsedMs ?: 0)}", "${svc?.lineCount ?: 0} lines · $live",
                    hig.tile(R.drawable.ic_mic, 0xFFFF3B30.toInt(), 30)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            }
            RecorderService.State.SAVING -> s.add(progressRow("Saving", "Finishing the transcript"))
            RecorderService.State.TRANSCRIBING -> {
                s.add(progressBarRow("Transcribing", RecorderService.transcribeStatus, RecorderService.transcribeFraction))
                s.add(hig.action("Cancel", destructive = true) { RecorderService.command(this, RecorderService.ACTION_CANCEL_TRANSCRIBE) })
            }
            RecorderService.State.SUMMARIZING -> {
                s.add(progressBarRow("Writing Notes", RecorderService.notesStatus, RecorderService.notesFraction))
                s.add(hig.action("Cancel", destructive = true) { RecorderService.command(this, RecorderService.ACTION_CANCEL_NOTES) })
            }
        }
        if (s.body.childCount == 0) record.removeAllViews()
        val busy = st == RecorderService.State.SAVING || st == RecorderService.State.TRANSCRIBING || st == RecorderService.State.SUMMARIZING || st == RecorderService.State.STARTING
        val recording = st == RecorderService.State.RECORDING || st == RecorderService.State.PAUSED
        val btn = hig.primary(if (recording) "Stop and Save" else "Start Recording") { toggleRecording() }
            .apply { isEnabled = !busy; alpha = if (busy) 0.5f else 1f }
        record.addView(btn, hig.mp().apply { topMargin = hig.dp(16); marginStart = hig.dp(16); marginEnd = hig.dp(16) })
    }

    private fun progressRow(title: String, sub: String): View =
        hig.row(title, sub, ProgressBar(this).apply { isIndeterminate = true }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) })

    private fun progressBarRow(title: String, sub: String, f: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hig.row("$title · ${(f * 100).toInt()}%", sub), hig.mp())
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = (f * 100).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(hig.color(R.color.hig_blue_fill))
        }, hig.mp().apply { marginStart = hig.dp(16); marginEnd = hig.dp(16); bottomMargin = hig.dp(10) })
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
        main.postDelayed({ drawRecord() }, 300)
    }

    // ------------------------------------------------------------------ language

    private fun drawLanguage() {
        language.removeAllViews()
        val cur = VoicePrefs.language(this)
        val plan = Plan.choose(cur, androidHindi, speechPackReady, SpeechClient.isTrusted(this), VoicePrefs.preferSpeechPack(this))
        val footer = when (plan) {
            Plan.MoonshineLive -> "English is transcribed live on this phone."
            Plan.AndroidLive -> "Hindi is transcribed live by Android's speech recognition on this phone." +
                if (cur == Lang.HINGLISH && android.os.Build.VERSION.SDK_INT < 34) " Switching to English words mid-sentence needs Android 14." else ""
            Plan.SpeechPackAfter -> "Hindi is transcribed by Speech Pack on this phone, right after you stop (no live captions)."
            is Plan.NeedsSetup -> when {
                plan.androidCanDownload -> "Android can download Hindi for offline speech recognition (Google, about 50–100 MB). Or install Speech Pack."
                plan.speechPackInstalled -> "Open Speech Pack and download its model to transcribe Hindi."
                androidHindi == AndroidHindi.UNKNOWN -> "Checking whether this phone can transcribe Hindi…"
                else -> "This phone's Android has no offline Hindi. Install the Speech Pack module (free) to transcribe Hindi and Hinglish on this phone."
            }
        }
        val s = hig.section(language, "Language", footer)
        Lang.entries.forEach { l ->
            val check = if (l == cur) TextView(this).apply { text = "✓"; textSize = 20f; setTextColor(hig.blue); contentDescription = "Selected" } else null
            s.add(hig.row(l.native, if (l.native != l.label) l.label else null, trailing = check,
                onClick = if (l != cur && RecorderService.state == RecorderService.State.IDLE) ({ VoicePrefs.setLanguage(this, l); drawLanguage() }) else null))
        }
        if (!cur.isHindi) return
        val a = hig.section(language)
        if (plan is Plan.NeedsSetup && plan.androidCanDownload) a.add(hig.action(if (androidDownloading) "Downloading Hindi…" else "Download Hindi for Android") {
            if (androidDownloading) return@action
            androidDownloading = true; drawLanguage()
            AndroidStt.download(this) { err ->
                androidDownloading = false
                android.widget.Toast.makeText(this, err ?: "Android is downloading Hindi. It's ready when this says \"live\".", android.widget.Toast.LENGTH_LONG).show()
                AndroidStt.check(this) { st -> androidHindi = st; VoicePrefs.setAndroidHindi(this, st); drawLanguage() }
            }
        })
        when {
            !SpeechClient.isInstalled(this) -> a.add(hig.action("Get Speech Pack") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ajitpal/dynamic-island-android/releases/latest"))) }
            })
            !speechPackReady -> a.add(hig.action("Open Speech Pack to Download Its Model") { SpeechClient.openApp(this) })
            else -> {}
        }
        if (androidHindi == AndroidHindi.READY && speechPackReady) {
            hig.section(language, footer = "Speech Pack made about half the mistakes of other on-device engines in our Hindi tests, but writes the transcript after the meeting instead of live.")
                .add(hig.toggle("Use Speech Pack for Hindi", VoicePrefs.preferSpeechPack(this)) { on -> VoicePrefs.setPreferSpeechPack(this, on); drawLanguage() })
        }
        if (a.body.childCount == 0) language.removeView(a.container)
    }

    // ------------------------------------------------------------------ import

    private fun drawImport() {
        importBox.removeAllViews()
        hig.section(importBox, footer = "Transcribe a recording you already have: a voice note, a call recording or meeting audio (MP3, M4A, WAV, OGG, FLAC). Uses the language above.")
            .add(hig.action("Import Audio File…") {
                if (RecorderService.state != RecorderService.State.IDLE) { android.widget.Toast.makeText(this, "Wait for the current recording or transcript to finish.", android.widget.Toast.LENGTH_SHORT).show(); return@action }
                pickAudio.launch(arrayOf("audio/*"))
            })
    }

    // ------------------------------------------------------------------ English model

    private fun drawEnglish() {
        english.removeAllViews()
        val sel = ModelStore.selected(this)
        val dl = ModelStore.downloading
        val s = hig.section(english, "English Speech Model", "Moonshine (MIT), downloaded once from Moonshine AI. Works offline.")
        ModelStore.Size.entries.forEach { m ->
            val present = ModelStore.isPresent(this, m)
            val check = if (m == sel) TextView(this).apply { text = "✓"; textSize = 20f; setTextColor(hig.blue); contentDescription = "Selected" } else null
            s.add(hig.row(m.label, (if (present) "Downloaded · ${Formatter.formatShortFileSize(this, ModelStore.sizeOnDisk(this, m))} · " else "Not downloaded · ") + m.blurb,
                trailing = check, onClick = if (m != sel && dl == null) ({ ModelStore.select(this, m); drawEnglish(); drawRecord() }) else null))
        }
        val a = hig.section(english)
        when {
            dl != null -> a.add(progressRow("Downloading ${dl.label}…", "You can leave this screen."))
            !ModelStore.isPresent(this, sel) -> a.add(hig.action("Download ${sel.label}") { download(sel) })
            RecorderService.state == RecorderService.State.IDLE -> a.add(hig.action("Delete ${sel.label}", destructive = true) { ModelStore.delete(this, sel); drawEnglish() })
        }
    }

    private fun download(m: ModelStore.Size) {
        ModelStore.download(this, m, progress = { _, _ -> }, done = { _, err ->
            runOnUiThread {
                if (err != null) android.widget.Toast.makeText(this, "Download failed: ${err.message ?: err.javaClass.simpleName}. Check your connection and try again.", android.widget.Toast.LENGTH_LONG).show()
                drawAll()
            }
        })
        main.postDelayed({ drawEnglish() }, 200)
    }

    // ------------------------------------------------------------------ AI notes

    private fun drawAi() {
        ai.removeAllViews()
        val client = com.ajit.dynamicisland.sdk.ai.AiClient
        val footer = when {
            !client.isInstalled(this) -> "Install the AI Engine module to get a summary, decisions and action items written on this phone after each meeting."
            !client.isTrusted(this) -> "The installed AI Engine isn't from the same developer, so it won't be used."
            else -> "Notes use the \"${VoicePrefs.template(this).label}\" template; pick another on a meeting's page. Hindi and Hinglish meetings get English notes."
        }
        val s = hig.section(ai, "AI Notes", footer)
        s.add(hig.toggle("Write Notes After Each Recording", VoicePrefs.autoNotes(this)) { on -> VoicePrefs.setAutoNotes(this, on) })
        val nano = com.ajit.dynamicisland.nano.Nano.lastStatus
        if (nano == null) Thread { com.ajit.dynamicisland.nano.Nano.status(); runOnUiThread { drawAi() } }.start()
        if (nano != null && nano != com.ajit.dynamicisland.nano.Nano.Status.UNAVAILABLE && nano != com.ajit.dynamicisland.nano.Nano.Status.ERROR)
            s.add(hig.toggle("Use Gemini Nano While Open", VoicePrefs.useNano(this), "Built into this phone; background notes still use AI Engine") { on -> VoicePrefs.setUseNano(this, on) })
        if (client.isInstalled(this)) s.add(hig.action("Open AI Engine") { client.openEngineApp(this) })
    }

    // ------------------------------------------------------------------ saved meetings

    private var shownCount = -1
    private fun drawSaved() {
        val all = Recordings.all(this)
        shownCount = all.size
        saved.removeAllViews()
        val s = hig.section(saved, "Saved Meetings", if (all.isEmpty()) "Your recordings and imports appear here." else null)
        if (all.isEmpty()) { s.add(hig.row("Nothing yet", titleColor = hig.secondary)); return }
        all.forEach { r ->
            val notes = NotesStore.load(this, r.id)
            val items = notes?.sections?.firstOrNull { it.first == "Action items" }?.second?.size ?: Notes.actionItems(r.lines).size
            val lang = runCatching { Lang.valueOf(r.language.uppercase()) }.getOrNull()?.takeIf { it.isHindi }?.let { " · ${it.native}" } ?: ""
            val sub = DateUtils.getRelativeTimeSpanString(r.startedAt).toString() + " · ${Notes.clock(r.durationMs)}$lang" +
                (if (r.imported) " · imported" else "") + (if (items > 0) " · $items to-dos" else "") + (if (notes != null) " · notes" else "")
            s.add(hig.row(r.title, sub, hig.tile(if (notes != null) R.drawable.ic_spark else R.drawable.ic_notes, if (notes != null) 0xFF5856D6.toInt() else 0xFFFF3B30.toInt(), 30), chevron = true,
                onClick = { startActivity(Intent(this, TranscriptActivity::class.java).putExtra(TranscriptActivity.EXTRA_ID, r.id)) }))
        }
    }
}
