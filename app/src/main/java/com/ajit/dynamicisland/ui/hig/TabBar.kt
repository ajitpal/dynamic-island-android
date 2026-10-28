package com.ajit.dynamicisland.ui.hig

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Bottom tab bar (HIG): always visible, icon + one-word label, badge only for things that need attention. */
class TabBar(private val hig: Hig, private val items: List<Pair<String, Int>>, private val onSelect: (Int) -> Unit) : LinearLayout(hig.ctx) {
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()
    private val badges = mutableListOf<TextView>()
    var selected = -1
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(hig.color(com.ajit.dynamicisland.R.color.hig_bar))
        addView(View(context).apply { setBackgroundColor(hig.separator) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, hig.dp(0.5f))))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        items.forEachIndexed { i, (label, icon) ->
            val cell = FrameLayout(context).apply {
                minimumHeight = hig.dp(56)
                setOnClickListener { select(i); onSelect(i) }
                isClickable = true; contentDescription = label
            }
            val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val iv = ImageView(context).apply { setImageResource(icon) }
            col.addView(iv, LayoutParams(hig.dp(26), hig.dp(26)))
            val tv = hig.text(label, Hig.Style.CAPTION_BOLD, hig.secondary).apply { setPadding(0, hig.dp(2), 0, 0); gravity = Gravity.CENTER }
            col.addView(tv)
            cell.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val badge = hig.text("", Hig.Style.CAPTION_BOLD, 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER; minWidth = hig.dp(18); setPadding(hig.dp(5), 0, hig.dp(5), 0)
                background = hig.rounded(0xFFFF3B30.toInt(), 9); visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cell.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, hig.dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = hig.dp(4); leftMargin = hig.dp(22)
            })
            row.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            icons += iv; labels += tv; badges += badge
        }
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun select(i: Int) {
        selected = i
        icons.forEachIndexed { k, v -> v.setColorFilter(if (k == i) hig.color(com.ajit.dynamicisland.R.color.hig_blue_fill) else hig.color(com.ajit.dynamicisland.R.color.hig_gray_fill)) }
        labels.forEachIndexed { k, v -> v.setTextColor(if (k == i) hig.blue else hig.secondary) }
        (getChildAt(1) as LinearLayout).let { row ->
            for (k in 0 until row.childCount) row.getChildAt(k).isSelected = k == i
        }
    }

    /** "!" for setup problems, a number for unread items, null to hide. */
    fun badge(i: Int, text: String?) {
        val b = badges[i]
        b.text = text.orEmpty(); b.visibility = if (text == null) View.GONE else View.VISIBLE
        val cell = (getChildAt(1) as LinearLayout).getChildAt(i)
        cell.contentDescription = items[i].first + (text?.let { if (it == "!") ", needs attention" else ", $it new" } ?: "")
    }
}
