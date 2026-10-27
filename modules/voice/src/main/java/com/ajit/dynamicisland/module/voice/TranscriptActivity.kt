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
import com.google.android.material.button.MaterialButton

/** One meeting: playback, possible action items and the timestamped transcript. */
class TranscriptActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "id" }

    private var player: MediaPlayer? = null
    private lateinit var playBtn: MaterialButton
    private lateinit var pos: TextView
    private val tick = Handler(Looper.getMainLooper())
    private lateinit var rec: Recording

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: intent.getStringExtra(IslandContract.EXTRA_ARG)
        rec = id?.let { Recordings.load(this, it) } ?: run { Toast.makeText(this, "Recording not found", Toast.LENGTH_SHORT).show(); finish(); return }

        val scroll = ScrollView(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(40)) }
        scroll.addView(c)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }

        val title = text(c, rec.title, 24f, bold = true).apply { setOnClickListener { rename(this) } }
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

        val items = Notes.actionItems(rec.lines)
        if (items.isNotEmpty()) {
            text(c, "Possible action items", 18f, bold = true)
            text(c, "Picked out by simple rules (\"will\", \"need to\", \"by Friday\"…). Check them; smarter on-device summaries come later.", 12f, alpha = 0.6f)
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

    private fun line(c: LinearLayout, l: Line, bullet: Boolean = false) {
        text(c, (if (bullet) "• " else "") + "${Notes.clock(l.startMs)}  ${l.text}", 15f).apply {
            setOnClickListener { seek(l.startMs) }
        }
    }

    private fun export() = Notes.toText(rec.title, DateUtils.formatDateTime(this, rec.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), rec.durationMs, rec.lines)

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
