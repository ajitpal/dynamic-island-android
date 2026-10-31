package com.ajit.dynamicisland.module.screen

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.screen.hig.Hig
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Screen Recorder (laid out like iPhone Settings): one Start/Stop button with the live time, the
 * options (sound, quality, frame rate, countdown) and the recordings saved by this app.
 * Choosing an option opens a short list with a checkmark, the way iOS settings do.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var hig: Hig
    private lateinit var page: Hig.Page
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var choosing: String? = null
    private val listener: () -> Unit = { handler.post { if (!isFinishing) render() } }
    private val ticker = object : Runnable {
        override fun run() { if (RecorderService.state == RecorderService.State.RECORDING) { render(); handler.postDelayed(this, 1000) } }
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { choose(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        page = hig.page("Screen Recorder")
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.column.addView(content, hig.mp())
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i
        }
        onBackPressedDispatcher.addCallback(this, back)
    }

    override fun onResume() {
        super.onResume()
        RecorderService.addListener(listener)
        render()
    }

    override fun onPause() {
        RecorderService.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun choose(what: String?) {
        choosing = what
        back.isEnabled = what != null
        render()
        page.root.scrollTo(0, 0)
    }

    private fun render() {
        content.removeAllViews()
        // The large title belongs to the main screen; an option list shows "‹ Screen Recorder" and its own title.
        page.column.getChildAt(0)?.visibility = if (choosing == null) View.VISIBLE else View.GONE
        when (choosing) {
            "sound" -> choice("Sound", Audio.entries.map { Triple(it.label, it.detail, it == Prefs.audio(this)) },
                "Phone sound comes from apps that allow it; most video and music apps do, some (like calls and protected videos) don't. Any sound option needs microphone access, which Android asks for once.") {
                Prefs.setAudio(this, Audio.entries[it])
            }
            "quality" -> choice("Quality", Quality.entries.map { Triple(it.label, null, it == Prefs.quality(this)) },
                "Higher quality makes bigger files. If this phone can't encode the full resolution, the recorder picks the largest size it can.") {
                Prefs.setQuality(this, Quality.entries[it])
            }
            "fps" -> choice("Frame Rate", listOf(Triple("30 fps", "Smaller files", Prefs.fps(this) == 30), Triple("60 fps", "Smoother scrolling and games", Prefs.fps(this) == 60)), null) {
                Prefs.setFps(this, if (it == 0) 30 else 60)
            }
            else -> main()
        }
    }

    private fun main() {
        handler.removeCallbacks(ticker)
        val st = RecorderService.state
        val status = hig.section(content)
        val (title, sub) = when (st) {
            RecorderService.State.IDLE -> "Ready" to "Start from here, the island tool or the tile. Android asks each time what to record."
            RecorderService.State.COUNTDOWN -> "Starting…" to "Recording begins in a moment"
            RecorderService.State.RECORDING -> "Recording · ${Plan.clock(RecorderService.elapsedMs())}" to "Stop here, in the island or from the notification"
            RecorderService.State.PAUSED -> "Paused · ${Plan.clock(RecorderService.elapsedMs())}" to "Nothing is being recorded"
            RecorderService.State.SAVING -> "Saving…" to "Finishing the video"
        }
        status.add(hig.row(title, sub, hig.tile(R.drawable.ic_screen_rec, if (st == RecorderService.State.IDLE) Hig.Tile.GRAY else Hig.Tile.RED)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        if (st == RecorderService.State.RECORDING) handler.postDelayed(ticker, 1000)
        if (st == RecorderService.State.RECORDING || st == RecorderService.State.PAUSED)
            status.add(hig.action(if (st == RecorderService.State.PAUSED) "Resume" else "Pause") {
                RecorderService.command(this, if (st == RecorderService.State.PAUSED) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
            })

        val label = when (st) {
            RecorderService.State.IDLE -> "Start Recording"
            RecorderService.State.COUNTDOWN -> "Cancel"
            RecorderService.State.SAVING -> "Saving…"
            else -> "Stop Recording"
        }
        val btn = hig.primary(label) {
            if (st == RecorderService.State.IDLE) startActivity(Intent(this, CaptureActivity::class.java))
            else RecorderService.command(this, RecorderService.ACTION_STOP)
        }.apply { isEnabled = st != RecorderService.State.SAVING; alpha = if (isEnabled) 1f else 0.5f }
        content.addView(btn, hig.mp().apply { topMargin = hig.dp(18); marginStart = hig.dp(16); marginEnd = hig.dp(16) })

        val opts = hig.section(content, "Options", footer = "Videos are saved to Movies › Dynamic Island. The island's own timer shows while you record, and it's in the video too.")
        val audio = Prefs.audio(this)
        opts.add(hig.nav("Sound", R.drawable.ic_speaker, Hig.Tile.PINK, audio.short) { choose("sound") })
        opts.add(hig.nav("Quality", R.drawable.ic_hd, Hig.Tile.BLUE, Prefs.quality(this).label) { choose("quality") })
        opts.add(hig.nav("Frame Rate", R.drawable.ic_screen_rec, Hig.Tile.INDIGO, "${Prefs.fps(this)} fps") { choose("fps") })
        opts.add(hig.toggle("3-Second Countdown", Prefs.countdown(this), null, R.drawable.ic_timer, Hig.Tile.ORANGE) { Prefs.setCountdown(this, it) })

        recordings()
    }

    private fun recordings() {
        val sec = hig.section(content, "Recordings")
        val loading = hig.row("Loading…")
        sec.add(loading)
        Thread {
            val items = Recordings.all(this)
            handler.post {
                if (isFinishing || choosing != null) return@post
                sec.clear()
                if (items.isEmpty()) sec.add(hig.row("No recordings yet", "They appear here and in your gallery."))
                items.forEach { r -> sec.add(item(r)) }
            }
        }.start()
    }

    private fun item(r: Recordings.Item): View {
        val ago = DateUtils.getRelativeTimeSpanString(r.addedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return hig.row(r.name.removeSuffix(".mp4"), "$ago · ${Plan.clock(r.durationMs)} · ${Formatter.formatShortFileSize(this, r.size)}",
            hig.tile(R.drawable.ic_play, Hig.Tile.RED), chevron = true,
            onClick = { open(OpenActivity.PLAY, r) },
            onLongClick = { menu(r) })
    }

    private fun open(what: String, r: Recordings.Item) =
        startActivity(Intent(this, OpenActivity::class.java).putExtra(com.ajit.dynamicisland.sdk.IslandContract.EXTRA_ARG, OpenActivity.arg(what, r.uri)))

    private fun menu(r: Recordings.Item) {
        MaterialAlertDialogBuilder(this).setTitle(r.name.removeSuffix(".mp4"))
            .setItems(arrayOf("Play", "Share", "Delete")) { _, i ->
                when (i) {
                    0 -> open(OpenActivity.PLAY, r)
                    1 -> open(OpenActivity.SHARE, r)
                    2 -> MaterialAlertDialogBuilder(this).setTitle("Delete this recording?").setMessage("It's removed from your phone and gallery.")
                        .setPositiveButton("Delete") { _, _ -> Recordings.delete(this, r.uri); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    /** A list of options with a checkmark on the chosen one; tapping one saves it and goes back. */
    private fun choice(title: String, rows: List<Triple<String, String?, Boolean>>, footer: String?, onPick: (Int) -> Unit) {
        content.addView(hig.link("‹ Screen Recorder") { choose(null) }.apply { setPadding(hig.dp(4), hig.dp(4), 0, hig.dp(4)); minHeight = hig.dp(48); gravity = Gravity.CENTER_VERTICAL })
        content.addView(hig.text(title, Hig.Style.TITLE2).apply { setPadding(hig.dp(4), hig.dp(4), 0, 0); isAccessibilityHeading = true })
        val sec = hig.section(content, footer = footer)
        rows.forEachIndexed { i, (t, d, on) ->
            val check = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_check); setColorFilter(hig.blue); visibility = if (on) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22))
            }
            sec.add(hig.row(t, d, trailing = check, onClick = { onPick(i); choose(null) }).apply {
                contentDescription = listOfNotNull(t, d, if (on) "selected" else null).joinToString(", ")
            })
        }
    }
}
