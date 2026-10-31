package com.ajit.dynamicisland.module.screen.hig

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.module.screen.R
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * A tiny UI kit in the style of iPhone Settings (Apple Human Interface Guidelines):
 * large titles, inset grouped lists, icon tiles, one primary button per screen.
 * Text sizes are in sp (they follow the system font size), every touch target is at least 48 dp,
 * and every control gets a TalkBack description.
 */
class Hig(val ctx: Context) {

    // ---- palette (values / values-night) ----
    fun color(res: Int) = ContextCompat.getColor(ctx, res)
    val bg get() = color(R.color.hig_bg)
    val card get() = color(R.color.hig_card)
    val label get() = color(R.color.hig_label)
    val secondary get() = color(R.color.hig_secondary)
    val separator get() = color(R.color.hig_separator)
    val blue get() = color(R.color.hig_blue)
    val blueFill get() = color(R.color.hig_blue_fill)
    val green get() = color(R.color.hig_green)
    val red get() = color(R.color.hig_red)
    val orange get() = color(R.color.hig_orange)

    /** iOS system fill colours for icon tiles (always with white glyphs). */
    object Tile {
        const val BLUE = 0xFF007AFF.toInt(); const val GREEN = 0xFF34C759.toInt(); const val ORANGE = 0xFFFF9500.toInt()
        const val RED = 0xFFFF3B30.toInt(); const val PURPLE = 0xFFAF52DE.toInt(); const val PINK = 0xFFFF2D55.toInt()
        const val INDIGO = 0xFF5856D6.toInt(); const val TEAL = 0xFF30B0C7.toInt(); const val GRAY = 0xFF8E8E93.toInt()
        const val YELLOW = 0xFFFFCC00.toInt(); const val BLACK = 0xFF1C1C1E.toInt()
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(v: Float) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ---- type scale (HIG text styles, Android sp) ----
    enum class Style(val sp: Float, val bold: Boolean) {
        LARGE_TITLE(32f, true), TITLE2(22f, true), TITLE3(20f, true), HEADLINE(17f, true), BODY(17f, false),
        CALLOUT(16f, false), SUBHEAD(15f, false), FOOTNOTE(13f, false), CAPTION(12f, false), CAPTION_BOLD(12f, true)
    }

    fun text(t: CharSequence?, style: Style = Style.BODY, color: Int = label, lines: Int = Int.MAX_VALUE) = TextView(ctx).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        if (style.bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (style == Style.LARGE_TITLE || style == Style.TITLE2) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        maxLines = lines; if (lines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun ripple(content: Drawable?, radiusDp: Int = 0): Drawable {
        val mask = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(radiusDp).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x22888888), content, mask)
    }

    fun mp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ---- page ----

    /** A scrolling page: optional large title, then sections. [column] is where content goes. */
    class Page(val root: View, val column: LinearLayout, val scroll: ScrollView)

    fun page(title: String?, subtitle: String? = null, topPad: Int = 12, bottomPad: Int = 32): Page {
        val scroll = ScrollView(ctx).apply { setBackgroundColor(bg); isFillViewport = true; clipToPadding = false }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(topPad), dp(16), dp(bottomPad)) }
        scroll.addView(col)
        if (title != null) {
            col.addView(text(title, Style.LARGE_TITLE).apply {
                setPadding(dp(4), dp(8), 0, dp(if (subtitle == null) 8 else 2))
                isAccessibilityHeading = true
            })
            subtitle?.let { col.addView(text(it, Style.SUBHEAD, secondary).apply { setPadding(dp(4), 0, dp(4), dp(8)) }) }
        }
        return Page(scroll, col, scroll)
    }

    // ---- inset grouped section ----

    /** A rounded card of rows with an optional header above it and footer below it. */
    inner class Section(val container: LinearLayout, val body: LinearLayout) {
        private var rows = 0
        fun add(v: View): View {
            if (rows++ > 0) body.addView(View(ctx).apply { setBackgroundColor(separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(0.5f))).apply { marginStart = dp(if (v.getTag(R.id.hig_indent) == true) 58 else 16) })
            body.addView(v, mp()); return v
        }
        fun clear() { body.removeAllViews(); rows = 0 }
    }

    fun section(parent: LinearLayout, header: String? = null, footer: CharSequence? = null, action: Pair<String, () -> Unit>? = null): Section {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        if (header != null || action != null) {
            val h = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(16), 0, dp(12), dp(6)) }
            h.addView(text(header?.uppercase(), Style.FOOTNOTE, secondary).apply { isAccessibilityHeading = true },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            action?.let { (t, f) -> h.addView(link(t, Style.FOOTNOTE) { f() }) }
            wrap.addView(h)
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = rounded(card, 12); clipToOutline = true }
        wrap.addView(body, mp())
        footer?.let { wrap.addView(text(it, Style.FOOTNOTE, secondary).apply { setPadding(dp(16), dp(6), dp(16), 0) }) }
        parent.addView(wrap, mp())
        return Section(wrap, body)
    }

    /** Footer-style text that isn't inside a section. */
    fun note(parent: LinearLayout, t: CharSequence) = parent.addView(text(t, Style.FOOTNOTE, secondary).apply { setPadding(dp(16), dp(10), dp(16), 0) })

    // ---- rows ----

    fun tile(iconRes: Int, tileColor: Int, sizeDp: Int = 30): View = FrameLayout(ctx).apply {
        background = rounded(tileColor, (sizeDp * 0.23f).toInt().coerceAtLeast(6))
        addView(ImageView(ctx).apply { setImageResource(iconRes); setColorFilter(0xFFFFFFFF.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(dp(sizeDp * 0.62f), dp(sizeDp * 0.62f), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun iconView(d: Drawable?, sizeDp: Int = 30): View = ImageView(ctx).apply {
        setImageDrawable(d); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** The common row: [leading] icon, title (+ subtitle), trailing value/view, optional chevron. Min height 48 dp. */
    fun row(
        title: CharSequence, subtitle: CharSequence? = null, leading: View? = null, value: CharSequence? = null,
        valueColor: Int = secondary, trailing: View? = null, chevron: Boolean = false, titleColor: Int = label,
        onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48); setPadding(dp(16), dp(10), dp(14), dp(10))
            if (onClick != null) { background = ripple(null); setOnClickListener { onClick() }; isClickable = true }
            onLongClick?.let { l -> setOnLongClickListener { l(); true } }
        }
        leading?.let { r.addView(it); (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14); r.setTag(R.id.hig_indent, true) }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, Style.BODY, titleColor))
        subtitle?.let { texts.addView(text(it, Style.FOOTNOTE, secondary).apply { setPadding(0, dp(2), 0, 0) }) }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value?.let { r.addView(text(it, Style.BODY, valueColor, lines = 1).apply { setPadding(dp(8), 0, 0, 0); maxWidth = dp(150) }) }
        trailing?.let { r.addView(it) }
        if (chevron) r.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(separator); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(6) })
        r.contentDescription = listOfNotNull(title, subtitle, value).joinToString(", ")
        return r
    }

    fun nav(title: CharSequence, iconRes: Int? = null, tileColor: Int = Tile.BLUE, value: CharSequence? = null, subtitle: CharSequence? = null, onClick: () -> Unit) =
        row(title, subtitle, iconRes?.let { tile(it, tileColor) }, value, chevron = true, onClick = onClick)

    fun toggle(title: CharSequence, checked: Boolean, subtitle: CharSequence? = null, iconRes: Int? = null, tileColor: Int = Tile.GREEN, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = MaterialSwitch(ctx).apply {
            isChecked = checked; contentDescription = title
            thumbTintList = null
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        return row(title, subtitle, iconRes?.let { tile(it, tileColor) }, trailing = sw, onClick = { sw.toggle() }).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** A blue (or red, for destructive) text button row. */
    fun action(title: CharSequence, destructive: Boolean = false, onClick: () -> Unit) =
        row(title, titleColor = if (destructive) red else blue, onClick = onClick)

    fun slider(title: String, from: Float, to: Float, value: Float, step: Float = 1f, format: (Float) -> String = { it.toInt().toString() }, onChange: (Float) -> Unit): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(4)) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(title, Style.BODY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(format(value), Style.BODY, secondary)
        head.addView(v)
        box.addView(head)
        box.addView(Slider(ctx).apply {
            valueFrom = from; valueTo = to; stepSize = step; this.value = value.coerceIn(from, to)
            contentDescription = title; isTickVisible = false; trackStopIndicatorSize = 0
            addOnChangeListener { _, x, _ -> v.text = format(x); onChange(x) }
        }, mp())
        return box
    }

    // ---- buttons ----

    /** The one filled button of a screen. */
    fun primary(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
        minHeight = dp(50); setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple(rounded(color(R.color.hig_blue_fill), 12), 12)
        setOnClickListener { onClick() }; isClickable = true
    }

    fun link(t: String, style: Style = Style.BODY, onClick: () -> Unit) = TextView(ctx).apply {
        text = t; setTextColor(blue); setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48); setPadding(dp(8), 0, dp(8), 0)
        background = ripple(null, 8); setOnClickListener { onClick() }; isClickable = true
    }

    /** A capsule button (shortcuts, Try it). Filled tint for the first/primary one. */
    fun pill(t: String, iconRes: Int? = null, filled: Boolean = false, onClick: () -> Unit): View {
        val bgCol = if (filled) color(R.color.hig_blue_fill) else color(R.color.hig_fill)
        val fg = if (filled) 0xFFFFFFFF.toInt() else blue
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40); setPadding(dp(14), 0, dp(14), 0)
            background = ripple(rounded(bgCol, 20), 20)
            iconRes?.let { addView(ImageView(ctx).apply { setImageResource(it); setColorFilter(fg); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }) }
            addView(text(t, Style.SUBHEAD, fg, lines = 1).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            setOnClickListener { onClick() }; isClickable = true; contentDescription = t
        }
    }

    /** A row of pills that scrolls sideways (keeps 48 dp targets with vertical padding). */
    fun pills(views: List<View>): View = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        views.forEach { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        addView(row)
    }

    /** A wrapping grid of pills (no sideways scrolling, nothing hidden). */
    fun flow(views: List<View>): View = com.google.android.material.chip.ChipGroup(ctx).apply {
        setPadding(dp(16), dp(10), dp(16), dp(10)); chipSpacingHorizontal = dp(8); chipSpacingVertical = dp(8)
        views.forEach { addView(it) }
    }

    /** Status line with icon + word + colour (never colour alone). */
    fun status(ok: Boolean, okText: String = "On", badText: String = "Set up"): TextView =
        text(if (ok) "✓ $okText" else "$badText ›", Style.BODY, if (ok) green else orange, lines = 1)
}
