package com.ajit.dynamicisland.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.ActionStyle
import com.ajit.dynamicisland.core.Colors
import com.ajit.dynamicisland.core.IslandAction
import com.ajit.dynamicisland.core.IslandActivity
import com.ajit.dynamicisland.core.Kind
import com.ajit.dynamicisland.core.chronoText
import com.ajit.dynamicisland.core.formatClock

/** Builds the compact (pill) and expanded (card) content for an activity. */
class ContentBinder(private val ctx: Context, private val host: Host) {

    interface Host {
        val palette: Palette
        fun hideContent(a: IslandActivity): Boolean
        fun open(a: IslandActivity)
        fun perform(a: IslandActivity, act: IslandAction)
        fun media(a: IslandActivity, cmd: MediaCmd)
        fun tool(id: String)
        fun addTicker(t: () -> Unit)
        fun submitInput(text: String)
        fun cancelInput()
    }

    enum class MediaCmd { PREV, TOGGLE, NEXT }

    class InputState(val hint: String, val onSend: (String) -> Unit) {
        var editText: EditText? = null
    }

    private fun dp(v: Int) = ctx.dp(v)
    private val pal get() = host.palette

    // ------------------------------------------------------------------ compact

    fun bindCompact(a: IslandActivity, left: FrameLayout, right: FrameLayout, size: Int) {
        left.removeAllViews(); right.removeAllViews()
        val hidden = host.hideContent(a)
        left.addView(leading(a, size), FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL))

        val trailing: View = when {
            a.kind == Kind.MEDIA -> EqualizerView(ctx).apply { color = a.accent; playing = a.isPlaying }
                .also { right.addView(it, FrameLayout.LayoutParams((size * 0.85f).toInt(), (size * 0.7f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)) }
            a.kind == Kind.PROGRESS -> {
                val ring = ProgressRing(ctx).apply { color = a.accent; progress = a.progress; indeterminate = a.indeterminate || a.progress < 0 }
                right.addView(ring, FrameLayout.LayoutParams((size * 0.8f).toInt(), (size * 0.8f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL))
                ring
            }
            a.chronoBase > 0 -> {
                val tv = label(chronoText(a), a.accent, 14f, bold = true)
                host.addTicker { tv.text = chronoText(a) }
                right.addView(tv, trailingParams()); tv
            }
            else -> {
                val txt = if (hidden) (a.appLabel ?: "Message") else (a.compactText ?: a.title)
                val color = when (a.kind) {
                    Kind.MESSAGE, Kind.NOTE, Kind.GENERIC, Kind.NAVIGATION, Kind.LIVE -> pal.fg
                    else -> a.accent
                }
                label(txt, color, 13f, bold = true).also { right.addView(it, trailingParams()) }
            }
        }
        trailing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun trailingParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
    )

    /** Round image (album art / avatar) or icon for the left side of the pill. */
    private fun leading(a: IslandActivity, size: Int): View {
        val iv = ImageView(ctx)
        when {
            a.image != null && !host.hideContent(a) -> {
                iv.setImageBitmap(a.image)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.clipRound(if (a.kind == Kind.MEDIA) dp(7).toFloat() else -1f)
            }
            a.icon != null && a.tintIcon -> {
                iv.setImageDrawable(a.icon.copy())
                iv.setColorFilter(a.accent)
                iv.setPadding(size / 10, size / 10, size / 10, size / 10)
            }
            a.icon != null -> {
                iv.setImageDrawable(a.icon.copy())
                iv.clipRound(-1f)
            }
            else -> {
                iv.setImageBitmap(letterAvatar(a.title, a.accent, size))
            }
        }
        return iv
    }

    // ------------------------------------------------------------------ expanded

    fun buildExpanded(a: IslandActivity, topPad: Int, input: InputState?): View {
        val hidden = host.hideContent(a)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), topPad, dp(18), dp(16))
        }

        // Header: artwork | title + text | accessory
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val big = dp(52)
        header.addView(leading(a, big), LinearLayout.LayoutParams(big, big))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = if (hidden) (a.appLabel ?: "New message") else a.title
        val body = if (hidden) "Unlock to view" else a.text
        texts.addView(label(title, pal.fg, 16f, bold = true))
        if (!body.isNullOrBlank()) {
            texts.addView(label(body, pal.fg2, 14f, maxLines = when (a.kind) { Kind.MESSAGE, Kind.GENERIC, Kind.NOTE -> 8; else -> 3 }))
        }
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })

        when {
            a.kind == Kind.MEDIA -> header.addView(
                EqualizerView(ctx).apply { color = a.accent; playing = a.isPlaying },
                LinearLayout.LayoutParams(dp(22), dp(18))
            )
            a.chronoBase > 0 -> {
                val tv = label(chronoText(a), a.accent, 24f, bold = true)
                host.addTicker { tv.text = chronoText(a) }
                header.addView(tv)
            }
            a.kind == Kind.PROGRESS && a.progress >= 0 -> header.addView(label("${(a.progress * 100).toInt()}%", a.accent, 20f, bold = true))
            a.image != null && a.icon != null && !a.tintIcon -> header.addView(
                ImageView(ctx).apply { setImageDrawable(a.icon.copy()) }, LinearLayout.LayoutParams(dp(20), dp(20))
            )
        }
        col.addView(header)

        // Media controls
        if (a.kind == Kind.MEDIA && a.media != null) {
            col.addView(mediaSection(a), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        } else if (a.progress >= 0) {
            col.addView(LineProgress(ctx).apply { color = a.accent; progress = a.progress },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(12) })
        }

        // Inline input (quick reply / note) or action buttons
        if (input != null) {
            col.addView(inputRow(input, a.accent), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        } else {
            val canOpen = a.contentIntent != null || a.packageName != ctx.packageName
            val base = if (hidden) emptyList() else a.actions
            // Always offer an explicit "Open" so tapping the island can be used just to preview.
            val acts = if (canOpen && a.onTap == null) base.take(2) + IslandAction("Open", onClick = { host.open(a) }) else base.take(3)
            if (acts.isNotEmpty() && a.kind != Kind.MEDIA) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                acts.forEach { act ->
                    row.addView(actionButton(act, a), LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
        }
        return col
    }

    private fun mediaSection(a: IslandActivity): View {
        val c = a.media!!
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LineProgress(ctx).apply { color = pal.fg }
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val elapsed = label("0:00", pal.fg3, 11f)
        val remain = label("", pal.fg3, 11f)
        times.addView(elapsed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(remain)
        box.addView(times, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val tick = {
            val st = c.playbackState
            val dur = c.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (st != null && dur > 0) {
                var pos = st.position
                if (st.state == PlaybackState.STATE_PLAYING) {
                    pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                }
                pos = pos.coerceIn(0, dur)
                bar.progress = pos.toFloat() / dur
                elapsed.text = formatClock(pos)
                remain.text = "-" + formatClock(dur - pos)
            }
        }
        tick(); host.addTicker(tick)

        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(res: Int, cmd: MediaCmd, sz: Int) = ImageView(ctx).apply {
            setImageResource(res); setColorFilter(pal.fg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(ctx, R.drawable.ripple_round)
            contentDescription = cmd.name
            setOnClickListener { host.media(a, cmd) }
        }.also { controls.addView(it, LinearLayout.LayoutParams(sz, sz).apply { marginStart = dp(14); marginEnd = dp(14) }) }
        btn(R.drawable.ic_prev, MediaCmd.PREV, dp(44))
        btn(if (a.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, MediaCmd.TOGGLE, dp(52))
        btn(R.drawable.ic_next, MediaCmd.NEXT, dp(44))
        box.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return box
    }

    private fun actionButton(act: IslandAction, a: IslandActivity): View = TextView(ctx).apply {
        text = act.title
        setTextColor(if (act.style == ActionStyle.NORMAL) pal.fg else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        val bg = when (act.style) {
            ActionStyle.POSITIVE -> Colors.GREEN
            ActionStyle.NEGATIVE -> Colors.RED
            ActionStyle.NORMAL -> pal.surface
        }
        background = rounded(bg, dp(20).toFloat())
        foreground = ContextCompat.getDrawable(ctx, R.drawable.ripple_round)
        setOnClickListener { host.perform(a, act) }
    }

    private fun inputRow(input: InputState, accent: Int): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(ctx).apply {
            hint = input.hint
            setHintTextColor(pal.fg3)
            setTextColor(pal.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(pal.surface, dp(22).toFloat())
            setPadding(dp(16), 0, dp(16), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { host.submitInput(v.text.toString()); true } else false
            }
        }
        input.editText = et
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val send = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_send); setColorFilter(Color.WHITE)
            background = circle(accent); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Send"
            setOnClickListener { host.submitInput(et.text.toString()) }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(pal.fg3)
            setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Cancel"
            setOnClickListener { host.cancelInput() }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(40), dp(44)))
        return row
    }

    // ------------------------------------------------------------------ tools panel

    fun buildTools(topPad: Int, torchOn: Boolean, input: InputState?): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), topPad, dp(14), dp(16))
        }
        col.addView(label("Island tools", pal.fg3, 12f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (input != null) {
            col.addView(inputRow(input, Colors.YELLOW), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
            return col
        }
        val tools = listOf(
            Triple(R.drawable.ic_torch, if (torchOn) "Light off" else "Flashlight", "torch"),
            Triple(R.drawable.ic_timer, "Timer 1m", "timer1"),
            Triple(R.drawable.ic_timer, "Timer 5m", "timer5"),
            Triple(R.drawable.ic_stopwatch, "Stopwatch", "stopwatch"),
            Triple(R.drawable.ic_screenshot, "Screenshot", "screenshot"),
            Triple(R.drawable.ic_note, "Pin note", "note"),
        )
        tools.chunked(3).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (icon, name, id) ->
                val active = id == "torch" && torchOn
                row.addView(toolButton(icon, name, active) { host.tool(id) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        return col
    }

    private fun toolButton(icon: Int, name: String, active: Boolean, onClick: () -> Unit): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
            contentDescription = name
        }
        val iv = ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(if (active) Color.BLACK else pal.fg)
            background = circle(if (active) Colors.YELLOW else pal.surface)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        box.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)))
        box.addView(label(name, pal.fg2, 11f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return box
    }

    // ------------------------------------------------------------------ helpers

    fun bubbleIcon(a: IslandActivity, size: Int): View = leading(a, size)

    private fun label(text: CharSequence, color: Int, sp: Float, bold: Boolean = false, maxLines: Int = 1) =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, 1.1f)
        }

    private fun Drawable.copy(): Drawable = constantState?.newDrawable()?.mutate() ?: this
}
