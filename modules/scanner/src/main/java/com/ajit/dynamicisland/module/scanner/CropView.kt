package com.ajit.dynamicisland.module.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** The photo with the page outline; drag the four circles onto the page's corners. */
class CropView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null; set(v) { field = v; invalidate() }
    var quad: Quad = Quad.inset(); set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x330A84FF }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val img = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private var dragging = -1

    init { contentDescription = "Page corners. Drag the four circles onto the corners of the page." }

    private fun layoutImage() {
        val b = bitmap ?: return
        val pad = 24 * d
        val s = minOf((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * s; val h = b.height * s
        img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun toView(p: Quad.P) = img.left + p.x * img.width() to img.top + p.y * img.height()

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        layoutImage()
        c.drawBitmap(b, null, img, bmpPaint)
        val pts = quad.points.map { toView(it) }
        path.reset(); path.moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { path.lineTo(it.first, it.second) }; path.close()
        c.drawPath(path, fill); c.drawPath(path, line)
        pts.forEach { (x, y) -> c.drawCircle(x, y, 11 * d, handle); c.drawCircle(x, y, 11 * d, ring) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pts = quad.points.map { toView(it) }
                val (i, dist) = pts.mapIndexed { i, (x, y) -> i to hypot(x - e.x, y - e.y) }.minBy { it.second }
                dragging = if (dist < 40 * d) i else -1
                if (dragging >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val p = Quad.P(((e.x - img.left) / img.width()).coerceIn(0f, 1f), ((e.y - img.top) / img.height()).coerceIn(0f, 1f))
                val l = quad.points.toMutableList().also { it[dragging] = p }
                quad = Quad(l[0], l[1], l[2], l[3], quad.found)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1 }
        }
        return true
    }
}
