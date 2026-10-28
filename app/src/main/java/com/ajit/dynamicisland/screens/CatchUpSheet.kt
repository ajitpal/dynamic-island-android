package com.ajit.dynamicisland.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.ai.CatchUp
import com.ajit.dynamicisland.core.ai.CatchUp.State
import com.ajit.dynamicisland.core.ai.CatchUp.Step
import com.ajit.dynamicisland.core.ai.SmartInbox
import com.ajit.dynamicisland.ui.hig.Hig

/**
 * The Catch Me Up sheet (HIG modal sheet): a grabber, Cancel on the leading side while it works,
 * Done on the trailing side when there's a result, the steps with live checkmarks, and the summary
 * in grouped sections. Pure view code: [render] draws any [State], so screenshots can show every one.
 */
class CatchUpSheet(
    private val ctx: Context, private val hig: Hig,
    private val onCancel: () -> Unit, private val onDone: () -> Unit,
    private val onRetry: () -> Unit, private val onMarkRead: () -> Unit, private val onOpenEngine: () -> Unit, private val onOpenSettings: () -> Unit,
) {
    private val cancel = hig.link("Cancel") { onCancel() }
    private val done = hig.link("Done", Hig.Style.HEADLINE) { onDone() }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(hig.dp(16), 0, hig.dp(16), hig.dp(28)) }
    private val scroll = ScrollView(ctx).apply { addView(body); isFillViewport = true }
    /** Redraw only when something other than the elapsed seconds changed, so TalkBack isn't interrupted every second. */
    private var drawn: Any? = null
    private var elapsedView: android.widget.TextView? = null

    val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = hig.rounded(hig.bg, 12)
        // Grabber.
        addView(View(ctx).apply { background = hig.rounded(hig.separator, 3); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(hig.dp(36), hig.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = hig.dp(6) })
        // Navigation bar: Cancel · title · Done.
        val bar = FrameLayout(ctx).apply { minimumHeight = hig.dp(52); setPadding(hig.dp(16), 0, hig.dp(16), 0) }
        bar.addView(cancel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(hig.text("Catch Me Up", Hig.Style.HEADLINE).apply { isAccessibilityHeading = true },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        bar.addView(done, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        listOf(cancel, done).forEach { it.minHeight = hig.dp(48); it.gravity = Gravity.CENTER_VERTICAL }
        addView(bar, hig.mp())
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(s: State) {
        cancel.visibility = if (s is State.Working) View.VISIBLE else View.INVISIBLE
        done.visibility = if (s is State.Working) View.INVISIBLE else View.VISIBLE
        val shape: Any = if (s is State.Working) s.copy(startedAt = 0) else s
        if (shape == drawn) { (s as? State.Working)?.let { elapsedView?.text = elapsedText(it) }; return }
        drawn = shape
        body.removeAllViews(); elapsedView = null
        when (s) {
            is State.Working -> working(s)
            is State.Done -> result(s)
            State.Empty -> message(R.drawable.ic_check, hig.green, "You're All Caught Up", "No new notifications in the last day.", null)
            is State.Failed -> failed(s)
            State.Idle -> {}
        }
    }

    private fun working(w: State.Working) {
        // Status: spinner, what's happening now, and how long it has taken.
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, hig.dp(20), 0, hig.dp(4)) }
        head.addView(ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(hig.secondary) },
            LinearLayout.LayoutParams(hig.dp(32), hig.dp(32)))
        val now = hig.text(CatchUp.stepText(w), Hig.Style.HEADLINE).apply {
            gravity = Gravity.CENTER; setPadding(0, hig.dp(12), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        head.addView(now)
        elapsedView = hig.text(elapsedText(w), Hig.Style.SUBHEAD, hig.secondary).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(4), 0, 0) }
        head.addView(elapsedView)
        body.addView(head, hig.mp())

        val steps = hig.section(body, footer = hint(w))
        val order = listOf(Step.INBOX, Step.LOADING, Step.READING, Step.WRITING)
        val current = if (w.step == Step.WAITING) Step.LOADING else w.step
        order.forEach { st ->
            val pos = order.indexOf(st).compareTo(order.indexOf(current))
            val title = when (st) {
                Step.INBOX -> if (w.count > 0) "Gathered ${w.count} notification${if (w.count == 1) "" else "s"}" else "Gather notifications"
                Step.LOADING -> if (w.step == Step.WAITING) "Wait for AI Engine" else "Load ${w.model ?: "AI model"}"
                Step.READING -> "Read them"
                Step.WRITING -> "Write summary"
                Step.WAITING -> ""
            }
            // Loading is skipped when the model is already in memory: show it as done once we're past it.
            steps.add(hig.row(title, leading = stepIcon(pos), titleColor = if (pos > 0) hig.secondary else hig.label))
        }
        w.text?.takeIf { it.isNotBlank() }?.let { summary(it, live = true) }
    }

    private fun elapsedText(w: State.Working): String {
        val s = CatchUp.elapsed(w)
        return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    }

    private fun hint(w: State.Working): String? = when (w.step) {
        Step.WAITING -> "Only one AI request runs at a time. This starts as soon as the other one is done."
        Step.LOADING -> "Loading takes longer after the model hasn't been used for a few minutes."
        Step.READING -> "Everything stays on this phone. Small phones can take up to a minute."
        else -> null
    }

    private fun stepIcon(pos: Int): View = when {
        pos < 0 -> ImageView(ctx).apply { setImageResource(R.drawable.ic_check); setColorFilter(hig.green) }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
        pos == 0 -> ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(hig.blue) }.also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
        else -> View(ctx).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(hig.dp(1.5f), hig.separator) } }
            .also { it.layoutParams = LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)) }
    }

    private fun result(d: State.Done) {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(d.at))
        hig.note(body, if (d.unchanged) "No new notifications since $time." else "${d.count} notification${if (d.count == 1) "" else "s"} · $time")
        summary(d.text, live = false)
        val s = hig.section(body, footer = "Written on this phone by AI Engine${d.model?.let { " ($it)" } ?: ""}. It can make mistakes.")
        s.add(hig.action("Mark All as Read") { onMarkRead() })
    }

    /** The model's two headings become two grouped sections. */
    private fun summary(text: String, live: Boolean) {
        SmartInbox.sections(text).forEach { (heading, bullets) ->
            val s = hig.section(body, heading)
            bullets.forEach { b -> s.add(hig.text(b, Hig.Style.BODY).apply {
                setPadding(hig.dp(16), hig.dp(11), hig.dp(16), hig.dp(11)); setTextIsSelectable(!live)
            }) }
        }
    }

    private fun failed(f: State.Failed) {
        val (label, action) = when (f.reason) {
            CatchUp.Reason.OFF -> "Open Settings" to onOpenSettings
            CatchUp.Reason.NO_ENGINE, CatchUp.Reason.NO_MODEL -> "Open AI Engine" to onOpenEngine
            CatchUp.Reason.BUSY, CatchUp.Reason.ERROR -> "Try Again" to onRetry
        }
        val icon = if (f.reason == CatchUp.Reason.BUSY) R.drawable.ic_spark else R.drawable.ic_info
        message(icon, if (f.reason == CatchUp.Reason.BUSY) hig.orange else hig.secondary, f.title, f.message, label to action)
    }

    private fun message(icon: Int, tint: Int, title: String, text: String, button: Pair<String, () -> Unit>?) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(hig.dp(8), hig.dp(28), hig.dp(8), hig.dp(8)) }
        box.addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(tint); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(hig.dp(44), hig.dp(44)))
        box.addView(hig.text(title, Hig.Style.TITLE3).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(14), 0, 0); isAccessibilityHeading = true })
        box.addView(hig.text(text, Hig.Style.BODY, hig.secondary).apply { gravity = Gravity.CENTER; setPadding(0, hig.dp(6), 0, hig.dp(22)) })
        button?.let { (t, f) -> box.addView(hig.primary(t) { f() }, hig.mp()) }
        body.addView(box, hig.mp())
    }
}
