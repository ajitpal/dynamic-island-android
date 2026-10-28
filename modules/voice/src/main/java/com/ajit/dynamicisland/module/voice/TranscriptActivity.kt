package com.ajit.dynamicisland.module.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.sdk.IslandContract
import com.ajit.dynamicisland.sdk.ai.AiClient
import com.google.android.material.button.MaterialButton

/** One meeting: playback, possible action items and the timestamped transcript. */
class TranscriptActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "id" }

    private var player: MediaPlayer? = null
    private lateinit var playBtn: MaterialButton
    private lateinit var pos: TextView
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var rec: Recording
    private lateinit var notesBox: LinearLayout
    private lateinit var askBox: LinearLayout
    private var asking = false
    private var focusAsk = false
    @Volatile private var askCancelled = false
    private lateinit var titleView: TextView
    private val onChange: () -> Unit = { if (::notesBox.isInitialized) { reloadTitle(); renderNotes() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arg = intent.getStringExtra(IslandContract.EXTRA_ARG)
        // "Ask last meeting" shortcut: open the newest recording with the question box focused.
        focusAsk = arg == VoiceModule.ARG_LAST_ASK || (arg == null && intent.getStringExtra(EXTRA_ID) == null)
        val id = intent.getStringExtra(EXTRA_ID) ?: arg?.takeIf { !focusAsk } ?: Recordings.all(this).firstOrNull()?.id
        rec = id?.let { Recordings.load(this, it) } ?: run { Toast.makeText(this, "No meetings recorded yet", Toast.LENGTH_SHORT).show(); finish(); return }

        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }

        val title = text(c, rec.title, 24f, bold = true).apply { setOnClickListener { rename(this) } }
        titleView = title
        text(c, DateUtils.formatDateTime(this, rec.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY) +
            " · ${Notes.clock(rec.durationMs)} · ${Notes.wordCount(rec.lines)} words · tap the title to rename", 13f, alpha = 0.7f)
        title.contentDescription = "Title, tap to rename"

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c.addView(row)
        val hasAudio = rec.audio(this).exists()
        playBtn = MaterialButton(this).apply { text = "▶ Play"; isEnabled = hasAudio; setOnClickListener { togglePlay() } }
        row.addView(playBtn)
        pos = TextView(this).apply { setPadding(dp(12), 0, 0, 0); alpha = 0.8f }
        row.addView(pos, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_VERTICAL })

        notesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(notesBox)
        renderNotes()
        askBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(askBox)
        buildAsk()
        // Find out (off the main thread) whether Gemini Nano is ready, then refresh.
        if (VoicePrefs.useNano(this) && com.ajit.dynamicisland.nano.Nano.lastStatus == null)
            Thread { com.ajit.dynamicisland.nano.Nano.status(); runOnUiThread { renderNotes(); buildAsk() } }.start()

        val items = Notes.actionItems(rec.lines)
        if (items.isNotEmpty() && NotesStore.load(this, rec.id) == null) {
            text(c, "Possible action items", 18f, bold = true)
            text(c, "Picked out by simple rules (\"will\", \"need to\", \"by Friday\"…). Check them, or tap Write notes for an AI summary.", 12f, alpha = 0.6f)
            items.forEach { l -> line(c, l, bullet = true) }
        }
        text(c, "Transcript", 18f, bold = true)
        if (rec.lines.isEmpty()) text(c, "No speech was recognised.", 14f, alpha = 0.6f)
        rec.lines.forEach { line(c, it) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        c.addView(actions)
        fun btn(t: String, f: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; setOnClickListener { f() }
        }.also { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }) }
        btn("Share") { share() }
        btn("Copy") { copy() }
        btn("Delete") { confirmDelete() }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); RecorderService.listeners += onChange; onChange() }
    override fun onPause() { RecorderService.listeners -= onChange; super.onPause() }

    private fun reloadTitle() { Recordings.load(this, rec.id)?.let { rec = it; titleView.text = it.title } }

    /** The AI notes card: notes if we have them, progress while writing, or a button to write them. */
    private fun renderNotes() {
        val box = notesBox
        box.removeAllViews()
        val notes = NotesStore.load(this, rec.id)
        val writing = RecorderService.state == RecorderService.State.SUMMARIZING && RecorderService.notesFor == rec.id
        val busy = RecorderService.state != RecorderService.State.IDLE && !writing
        text(box, "✨ Notes", 18f, bold = true)
        when {
            writing -> {
                text(box, "${RecorderService.notesStatus} · ${(RecorderService.notesFraction * 100).toInt()}%", 14f, alpha = 0.8f)
                RecorderService.notesPreview.takeIf { it.isNotBlank() }?.let { text(box, it.takeLast(600), 13f, alpha = 0.6f) }
                box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Cancel"; setOnClickListener { RecorderService.command(this@TranscriptActivity, RecorderService.ACTION_CANCEL_NOTES) }
                })
                return
            }
            notes != null && !notes.isEmpty() -> {
                notes.sections.filter { it.second.isNotEmpty() }.forEach { (h, items) ->
                    text(box, h, 15f, bold = true)
                    items.forEach { text(box, "• $it", 15f) }
                }
                text(box, "${notes.template.label} notes by ${notes.model ?: "the AI Engine"}, on this phone. Small models make mistakes: check important details in the transcript.", 12f, alpha = 0.6f)
            }
            Brain.anyInstalled(this) -> {}
            !AiClient.isInstalled(this) -> {
                text(box, "Install the AI Engine module (or turn on Gemini Nano in Meeting Notes, on phones that have it) to get a summary, decisions and action items written on this phone.", 14f, alpha = 0.75f)
                return
            }
            else -> { text(box, "The installed AI Engine isn't signed by the same developer, so Meeting Notes won't send it your transcript.", 14f, alpha = 0.75f); return }
        }
        RecorderService.notesErrors[rec.id]?.let { text(box, "⚠ $it", 14f) }
        if (rec.lines.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(row)
        row.addView(MaterialButton(this).apply {
            text = if (notes == null) "Write notes" else "Rewrite"
            isEnabled = !busy
            setOnClickListener { pickTemplate(notes?.template ?: VoicePrefs.template(this@TranscriptActivity)) }
        })
        if (busy) text(box, "Busy recording or writing other notes; try again when it's done.", 12f, alpha = 0.6f)
    }

    // ---- Ask about this meeting ----

    private fun buildAsk() {
        val box = askBox
        box.removeAllViews()
        if (rec.lines.isEmpty() || !Brain.anyInstalled(this)) return
        text(box, "💬 Ask about this meeting", 18f, bold = true)
        val input = EditText(this).apply { hint = "e.g. What did we decide about the budget?"; minLines = 1; maxLines = 3 }
        if (focusAsk) { focusAsk = false; input.post { input.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) } }
        box.addView(input)
        val live = TextView(this).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(6), 0, dp(6)) }
        val btn = MaterialButton(this).apply { text = if (asking) "Stop" else "Ask" }
        btn.setOnClickListener {
            if (asking) { askCancelled = true; return@setOnClickListener }
            val q = input.text.toString().trim()
            if (q.length < 3) { Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            ask(q, live, btn) { input.setText("") }
        }
        box.addView(btn)
        box.addView(live)
        QaStore.load(this, rec.id).forEach { item ->
            text(box, "Q: ${item.question}", 14f, bold = true)
            box.addView(answerView(item.answer + (item.by?.let { "\n— ${it}" } ?: "")))
        }
    }

    private fun ask(q: String, live: TextView, btn: MaterialButton, done: () -> Unit) {
        asking = true; askCancelled = false; btn.text = "Stop"
        live.text = "Thinking… (the first question loads the model, which takes a few seconds)"
        val notes = NotesStore.load(this, rec.id)
        Thread({
            val brain = Brain(this)
            val result = try {
                val (maxIn, _) = brain.capacity() ?: Pair(0, "")
                if (maxIn == 0) Brain.Out(null, 0, "No AI is available. Install the AI Engine module.")
                else {
                    val notesChars = notes?.let { Summary.format(it.sections).length } ?: 0
                    val budget = (maxIn - Qa.SYSTEM.length - notesChars - q.length - 200).coerceAtLeast(1200)
                    brain.generate(Qa.SYSTEM, Qa.prompt(q, notes, Qa.excerpts(rec.lines, q, budget)), Qa.ANSWER_TOKENS, 0.2f,
                        onPartial = { p -> runOnUiThread { if (!askCancelled) live.text = p } }, isCancelled = { askCancelled })
                }
            } finally { brain.close() }
            runOnUiThread {
                asking = false
                if (result.text != null && !askCancelled) {
                    QaStore.add(this, rec.id, QaItem(q, result.text, System.currentTimeMillis(), brain.usedBy.joinToString(" + ").ifEmpty { null }))
                    done(); buildAsk()
                } else { btn.text = "Ask"; live.text = if (askCancelled) "" else "⚠ ${result.error ?: "Couldn't answer"}" }
            }
        }, "ask").start()
    }

    /** An answer whose [m:ss] timestamps jump the player to that moment. */
    private fun answerView(answer: String): TextView {
        val span = android.text.SpannableString(answer)
        Qa.stamps(answer).forEach { (s, e, ms) ->
            span.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) { seek(ms) }
            }, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(this).apply {
            text = span; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(2), 0, dp(12))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    private fun pickTemplate(current: Template) {
        val all = Template.entries
        AlertDialog.Builder(this).setTitle("What kind of conversation?")
            .setSingleChoiceItems(all.map { "${it.label}  (${it.sections.joinToString(", ")})" }.toTypedArray(), all.indexOf(current)) { d, which ->
                d.dismiss(); val t = all[which]
                VoicePrefs.setTemplate(this, t)
                try { RecorderService.summarize(this, rec.id, t) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
                notesBox.postDelayed({ renderNotes() }, 400)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun line(c: LinearLayout, l: Line, bullet: Boolean = false) {
        text(c, (if (bullet) "• " else "") + "${Notes.clock(l.startMs)}  ${l.text}", 15f).apply {
            setOnClickListener { seek(l.startMs) }
        }
    }

    private fun export() = Notes.toText(rec.title, DateUtils.formatDateTime(this, rec.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), rec.durationMs, rec.lines, NotesStore.load(this, rec.id)) +
        QaStore.load(this, rec.id).takeIf { it.isNotEmpty() }?.let { qa -> "\nQuestions\n" + qa.reversed().joinToString("\n") { "Q: ${it.question}\nA: ${it.answer}\n" } }.orEmpty()

    private fun share() = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, rec.title).putExtra(Intent.EXTRA_TEXT, export()), "Share notes"))

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(rec.title, export()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun rename(tv: TextView) {
        val et = EditText(this).apply { setText(rec.title); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("Rename").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim().ifEmpty { rec.title }
                Recordings.rename(this, rec.id, t); rec = rec.copy(title = t.take(80)); tv.text = rec.title
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("Delete this meeting?").setMessage("The audio and transcript are removed from this phone.")
            .setPositiveButton("Delete") { _, _ -> release(); Recordings.delete(this, rec.id); finish() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        return try {
            MediaPlayer().apply {
                setDataSource(rec.audio(this@TranscriptActivity).absolutePath); prepare()
                setOnCompletionListener { playBtn.text = "▶ Play" }
            }.also { player = it }
        } catch (e: Exception) { Toast.makeText(this, "Can't play this recording", Toast.LENGTH_SHORT).show(); null }
    }

    private fun togglePlay() {
        val p = ensurePlayer() ?: return
        if (p.isPlaying) { p.pause(); playBtn.text = "▶ Play" } else { p.start(); playBtn.text = "❚❚ Pause"; tick.post(ticker) }
    }

    private fun seek(ms: Long) {
        val p = ensurePlayer() ?: return
        p.seekTo(ms.toInt().coerceAtLeast(0)); if (!p.isPlaying) { p.start(); playBtn.text = "❚❚ Pause"; tick.post(ticker) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            pos.text = "${Notes.clock(p.currentPosition.toLong())} / ${Notes.clock(p.duration.toLong())}"
            if (p.isPlaying) tick.postDelayed(this, 500)
        }
    }

    private fun release() { tick.removeCallbacks(ticker); player?.release(); player = null }
    override fun onStop() { player?.takeIf { it.isPlaying }?.pause(); if (::playBtn.isInitialized) playBtn.text = "▶ Play"; super.onStop() }
    override fun onDestroy() { release(); super.onDestroy() }
}
