package com.ajit.dynamicisland.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ajit.dynamicisland.core.IslandActivity
import com.ajit.dynamicisland.core.IslandRepository

/**
 * The top of a phone with the island on it. [demo] shows a sample message preview (onboarding,
 * screenshots); otherwise it mirrors what the real island shows right now.
 */
class IslandPreview(ctx: Context, private val demo: Boolean = false) : FrameLayout(ctx) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private val pill = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF3A2F7A.toInt(), 0xFF1B4A8C.toInt(), 0xFF0F7C8C.toInt())).apply {
            cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        clipToOutline = true
        addView(pill, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(10) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        render()
    }

    private fun label(t: CharSequence?, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun render() {
        pill.removeAllViews()
        val a: IslandActivity? = if (demo) null else IslandRepository.activities.value.values.maxByOrNull { it.priority }
        when {
            demo -> {
                pill.orientation = LinearLayout.VERTICAL
                pill.setPadding(dp(16), dp(14), dp(16), dp(14))
                pill.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(26).toFloat() }
                val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(label("P", 15f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF25D366.toInt()) }
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label("Priya", 15f, Color.WHITE, true))
                col.addView(label("Dinner at 7? I booked the table", 13f, 0xFFAEAEB2.toInt()))
                head.addView(col)
                pill.addView(head)
                val acts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
                listOf("Reply", "Mark read", "Open").forEach { t ->
                    acts.addView(label(t, 13f, Color.WHITE).apply {
                        gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6))
                        background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(16).toFloat() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                }
                pill.addView(acts)
                contentDescription = "Example: the island showing a message from Priya with Reply, Mark read and Open"
            }
            a != null -> {
                pill.orientation = LinearLayout.HORIZONTAL
                pill.setPadding(dp(10), dp(6), dp(14), dp(6))
                pill.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(18).toFloat() }
                a.icon?.let { d ->
                    pill.addView(ImageView(context).apply {
                        setImageDrawable(d.constantState?.newDrawable()?.mutate() ?: d)
                        if (a.tintIcon) setColorFilter(a.accent)
                    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                }
                pill.addView(label(a.compactText ?: a.title, 13f, Color.WHITE, true).apply { maxWidth = dp(200) })
                contentDescription = "The island now shows: ${a.title}"
            }
            else -> {
                pill.orientation = LinearLayout.HORIZONTAL
                pill.setPadding(0, 0, 0, 0)
                pill.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
                pill.addView(FrameLayout(context), LinearLayout.LayoutParams(dp(14), dp(14)))
                contentDescription = "Nothing is running, so the island is hidden in the camera hole"
            }
        }
    }
}
