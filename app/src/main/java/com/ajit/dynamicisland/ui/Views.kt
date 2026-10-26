package com.ajit.dynamicisland.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
}

fun circle(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color)
}

/** Clip a view to a rounded rect (or circle when radius < 0). */
fun View.clipRound(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (radius < 0) outline.setOval(0, 0, view.width, view.height)
            else outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = true
}

/** Damped spring curve – gives the island its "liquid" overshoot. */
class SpringInterpolator(private val damping: Float = 6.5f, private val frequency: Float = 9f) : TimeInterpolator {
    override fun getInterpolation(t: Float): Float =
        (1 - exp(-damping * t) * cos(frequency * t)).toFloat()
}

fun letterAvatar(name: CharSequence?, color: Int, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    val letter = name?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
    p.color = Color.WHITE
    p.textSize = size * 0.45f
    p.typeface = Typeface.DEFAULT_BOLD
    p.textAlign = Paint.Align.CENTER
    val y = size / 2f - (p.descent() + p.ascent()) / 2
    c.drawText(letter, size / 2f, y, p)
    return bmp
}

class ProgressRing(ctx: Context) : View(ctx) {
    private val stroke = ctx.dp(3f).toFloat()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var rotation0 = 0f
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { rotation0 = it.animatedValue as Float; invalidate() }
    }

    var color: Int = Color.WHITE
        set(v) { field = v; fg.color = v; bg.color = v; bg.alpha = 70; invalidate() }
    var progress: Float = 0f
        set(v) { field = v; invalidate() }
    var indeterminate: Boolean = false
        set(v) { field = v; if (v && isAttachedToWindow) spin.start() else spin.cancel(); invalidate() }

    init { color = Color.WHITE }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (indeterminate) spin.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); spin.cancel() }

    override fun onDraw(canvas: Canvas) {
        val s = stroke / 2
        oval.set(s, s, width - s, height - s)
        canvas.drawArc(oval, 0f, 360f, false, bg)
        if (indeterminate) canvas.drawArc(oval, rotation0 - 90f, 100f, false, fg)
        else canvas.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, fg)
    }
}

class LineProgress(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var color: Int = Color.WHITE
        set(v) { field = v; fg.color = v; bg.color = Color.WHITE; bg.alpha = 40; invalidate() }
    var progress: Float = 0f
        set(v) { field = v; invalidate() }

    init { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, bg)
        r.set(0f, 0f, width * progress.coerceIn(0f, 1f), h)
        canvas.drawRoundRect(r, h / 2, h / 2, fg)
    }
}

/** Animated "now playing" bars. */
class EqualizerView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    var color: Int = Color.WHITE
        set(v) { field = v; paint.color = v; invalidate() }
    var playing: Boolean = false
        set(v) { field = v; if (v && isAttachedToWindow) anim.start() else anim.cancel(); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing) anim.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }

    override fun onDraw(canvas: Canvas) {
        val bars = 4
        val bw = width / (bars * 2f - 1)
        for (i in 0 until bars) {
            val f = if (playing) 0.25f + 0.75f * abs(sin(t * (1 + i * 0.35f) + i * 1.3f)) else 0.2f
            val h = height * f
            val x = i * bw * 2
            rect.set(x, (height - h) / 2, x + bw, (height + h) / 2)
            canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }
}
