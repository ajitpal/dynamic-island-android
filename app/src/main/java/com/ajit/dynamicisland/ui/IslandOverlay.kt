package com.ajit.dynamicisland.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.core.Colors
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.core.inbox.InboxStore
import com.ajit.dynamicisland.core.tools.ToolCatalog
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.core.tools.ToolLayout
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.IslandAction
import com.ajit.dynamicisland.core.IslandDebug
import com.ajit.dynamicisland.core.IslandActivity
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.IslandTools
import com.ajit.dynamicisland.core.Kind
import com.ajit.dynamicisland.core.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The island itself: a black pill drawn around the punch-hole camera that morphs between
 * IDLE (just the pill), COMPACT (icon left of the camera, info right of it) and EXPANDED
 * (a full card with controls). Lives in a TYPE_ACCESSIBILITY_OVERLAY window, so it draws
 * above the status bar, the lock screen and full-screen apps without "draw over apps".
 */
class IslandOverlay(
    base: Context,
    private val prefs: Prefs,
    /** Runs an AccessibilityService global action (screenshot, lock, recents…). */
    private val globalAction: (Int) -> Boolean,
) : ContentBinder.Host {

    enum class Mode { HIDDEN, IDLE, COMPACT, EXPANDED }

    private data class Geo(
        val screenW: Int, val screenH: Int, val cx: Int, val top: Int,
        val idleW: Int, val idleH: Int, val camGap: Int, val expandedW: Int,
        /** Height of the compact pill; the idle pill is just the camera hole. */
        val compactH: Int, val compactW: Int,
    )

    companion object {
        const val TOOLS_ID = "__tools"
        const val INBOX_ID = "__inbox"
        fun isPanel(id: String?) = id == TOOLS_ID || id == INBOX_ID
    }

    private val ctx: Context = ContextThemeWrapper(base, R.style.Theme_Island_Overlay)
    private val wm = base.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val binder = ContentBinder(ctx, this)
    private fun dp(v: Int) = ctx.dp(v)
    private fun dp(v: Float) = ctx.dp(v)

    // ---- views
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        clipChildren = false; clipToPadding = false
    }
    private val leftSpacer = View(ctx)
    private val pillBg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(ctx).apply {
        background = pillBg
        clipToOutline = true
        contentDescription = "Dynamic Island"
    }
    private val compactRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val compactLeft = FrameLayout(ctx)
    private val compactGap = View(ctx)
    private val compactRight = FrameLayout(ctx)
    private val expandedHost = FrameLayout(ctx)
    private val privacyDot = View(ctx)
    private val bubble = FrameLayout(ctx).apply { background = circle(Color.BLACK); clipToOutline = true }

    private val params = WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "DynamicIsland"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }

    // ---- state
    private var attached = false
    private lateinit var geo: Geo
    private var mode = Mode.HIDDEN
    private var lastKey = ""
    private var curW = 0
    private var curH = 0
    private var curR = 0f
    private var bubbleW = 0
    private var animator: ValueAnimator? = null
    private var expandedId: String? = null
    private var stickyPeekId: String? = null
    private var preferredId: String? = null
    private var toolsPage = 0
    @Volatile private var inboxCache: List<InboxEntry> = emptyList()
    @Volatile private var inboxUnread = 0
    private val dismissed = HashMap<String, Int>()
    private var foregroundPkg: String? = null
    private var statusBarHidden = false
    private var seenStatusBar = false
    private var input: ContentBinder.InputState? = null
    private var inputFor: String? = null
    private var primary: IslandActivity? = null
    private var secondary: IslandActivity? = null
    private val tickers = mutableListOf<() -> Unit>()

    private val collapseRunnable = Runnable { if (input == null) collapse() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            tickers.forEach { it() }
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun attach() {
        if (attached) return
        geo = computeGeo()
        compactRow.addView(compactLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        compactRow.addView(compactGap, LinearLayout.LayoutParams(geo.camGap, 1))
        compactRow.addView(compactRight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pill.addView(compactRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pill.addView(expandedHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        privacyDot.background = circle(Color.GREEN)
        pill.addView(privacyDot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
        root.addView(leftSpacer, LinearLayout.LayoutParams(0, 1))
        root.addView(pill, LinearLayout.LayoutParams(geo.idleW, geo.idleH))
        root.addView(bubble, LinearLayout.LayoutParams(geo.compactH, geo.compactH).apply { marginStart = dp(6) })
        bubble.isVisible = false
        expandedHost.isVisible = false

        val detector = GestureDetector(ctx, gestures)
        pill.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && mode == Mode.EXPANDED) rescheduleCollapse()
            detector.onTouchEvent(e)
            true
        }
        bubble.setOnClickListener {
            secondary?.let { preferredId = it.id; haptic(); refresh() }
        }
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && mode == Mode.EXPANDED && input == null) collapse()
            false
        }
        // Hide while an app is full-screen (status bar hidden): videos, games, camera.
        // Some ROMs (HyperOS) never report the status bar as visible to an overlay window, which
        // would hide the island forever. So only trust "hidden" after we've seen it visible once.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sbVisible = insets.isVisible(WindowInsets.Type.statusBars())
            if (sbVisible) seenStatusBar = true
            val hidden = seenStatusBar && !sbVisible
            IslandDebug.statusBarReported = if (sbVisible) "visible" else "hidden"
            if (hidden != statusBarHidden) { statusBarHidden = hidden; handler.post { refresh() } }
            v.onApplyWindowInsets(insets)
        }

        curW = geo.idleW; curH = geo.idleH; curR = geo.idleH / 2f
        applyPill(curW, curH, curR)
        try {
            wm.addView(root, params)
            attached = true
            IslandDebug.overlayAttached = "yes"
        } catch (e: Exception) {
            IslandDebug.overlayAttached = "FAILED: ${e.javaClass.simpleName}: ${e.message}"
            return
        }
        setWindow(curW, curH)
        refresh()
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        if (attached) try { wm.removeView(root) } catch (_: Exception) {}
        attached = false
    }

    fun onSettingsChanged() {
        if (!attached) return
        geo = computeGeo()
        (compactGap.layoutParams as LinearLayout.LayoutParams).width = geo.camGap
        compactGap.requestLayout()
        lastKey = ""
        refresh()
    }

    fun setForeground(pkg: String) {
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        IslandDebug.foreground = pkg
        refresh()
    }

    // ------------------------------------------------------------------ geometry

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        return if (h in dp(16)..dp(64)) h else dp(28)
    }

    /**
     * Size everything from what the system reports, so the island adapts to the phone, its
     * camera hole and the "Display size" setting. OEM cutout rects are sanity-checked: HyperOS
     * often pads the cutout to the full status-bar height, which made the old pill a big block.
     */
    private fun computeGeo(): Geo {
        val bounds = wm.maximumWindowMetrics.bounds
        val sw = bounds.width(); val sh = bounds.height()
        val sbH = statusBarHeight()
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val cut = try {
            display?.cutout?.boundingRects
                ?.filter { it.top < sbH && it.width() in 1 until sw / 4 && it.height() in 1..sbH * 2 }
                ?.minByOrNull { abs(it.centerX() - sw / 2) }
        } catch (e: Exception) { null }

        // A punch hole is round: its real diameter is the smaller side of the (possibly padded) rect.
        val maxCam = (sbH * 0.6f).roundToInt()
        val camD = (cut?.let { minOf(it.width(), it.height()) }?.takeIf { it >= dp(5) } ?: (sbH * 0.5f).roundToInt())
            .coerceAtMost(maxCam)
        val cx = cut?.centerX() ?: (sw / 2)
        val camCy = if (cut != null && cut.height() <= cut.width() * 1.3f) cut.centerY() else (cut?.let { it.top + camD / 2 } ?: (sbH / 2))

        // Resting island = exactly the camera hole (black on black → invisible). Fine-tune with holeAdjust.
        val idle = (camD + prefs.holeAdjust).coerceAtLeast(dp(4))
        val baseH = (camD + dp(10)).coerceIn(dp(20), (sbH * 0.95f).roundToInt().coerceAtLeast(dp(20)))
        val compactH = (baseH * prefs.heightScale).roundToInt().coerceAtLeast(idle + dp(4))
        val compactW = ((compactH * 2.6f) * prefs.widthScale).roundToInt().coerceAtLeast(camD + dp(14))
        // The window's top is the compact pill's top; smaller shapes are centred on the camera inside it.
        val top = max(0, camCy - compactH / 2)
        IslandDebug.geometry = "statusBar=${sbH}px cutout=${cut?.toShortString() ?: "none"} cam=${camD}px idle=${idle}px compact=${compactW}x${compactH}px"
        return Geo(sw, sh, cx, top, idle, idle, camD + dp(10), sw - dp(16), compactH, compactW)
    }

    // ------------------------------------------------------------------ state machine

    private fun isVisible(a: IslandActivity): Boolean {
        if (!prefs.kindEnabled(a.kind)) return false
        dismissed[a.id]?.let { if (it == a.contentHash) return false else dismissed.remove(a.id) }
        if (prefs.hideForegroundApp && a.kind != Kind.CALL && a.kind != Kind.SYSTEM &&
            a.packageName != ctx.packageName && a.packageName == foregroundPkg
        ) return false
        return true
    }

    private fun hiddenByEnvironment(): Boolean {
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && prefs.hideLandscape) { IslandDebug.hiddenReason = "landscape"; return true }
        if (statusBarHidden && prefs.hideFullscreen && expandedId == null) {
            // Still show calls even in full screen.
            val hide = IslandRepository.activities.value.values.none { it.kind == Kind.CALL && isVisible(it) }
            IslandDebug.hiddenReason = if (hide) "full-screen app" else "-"
            return hide
        }
        IslandDebug.hiddenReason = "-"
        return false
    }

    fun refresh(force: Boolean = false) {
        if (!attached) return
        if (input != null && !force) return // don't rebuild while the user is typing
        val visible = IslandRepository.activities.value.values
            .filter { isVisible(it) }
            .sortedWith(compareByDescending<IslandActivity> { if (it.id == preferredId) 1000 else it.priority }.thenByDescending { it.updatedAt })
        if (preferredId != null && visible.none { it.id == preferredId }) preferredId = null
        primary = visible.firstOrNull()
        secondary = visible.getOrNull(1)

        // A sticky peek (incoming call) closes as soon as it is no longer a peek (answered / ended).
        stickyPeekId?.let { id ->
            val a = visible.firstOrNull { it.id == id }
            if (a == null || !a.peek) { stickyPeekId = null; if (expandedId == id) expandedId = null }
        }
        val exp = expandedId
        val expActivity = if (exp != null && !isPanel(exp)) visible.firstOrNull { it.id == exp } else null
        if (exp != null && !isPanel(exp) && expActivity == null) expandedId = null

        val target = when {
            hiddenByEnvironment() -> Mode.HIDDEN
            isPanel(expandedId) -> Mode.EXPANDED
            expActivity != null -> Mode.EXPANDED
            primary != null -> Mode.COMPACT
            prefs.showIdle || prefs.calibrate -> Mode.IDLE
            else -> Mode.HIDDEN
        }
        render(target, if (target == Mode.EXPANDED) expActivity else primary)
    }

    private fun render(target: Mode, a: IslandActivity?) {
        val key = "$target:${a?.id}:$expandedId:${inputFor}"
        val swap = key != lastKey
        lastKey = key
        mode = target
        IslandDebug.mode = "$target ${a?.kind ?: ""} ${a?.title ?: ""}".trim()
        tickers.clear()
        val g = geo
        val privacy = prefs.fPrivacyDots && (IslandRepository.cameraInUse.value || IslandRepository.micInUse.value)
        privacyDot.background = circle(if (IslandRepository.cameraInUse.value) 0xFF30D158.toInt() else 0xFFFF9F0A.toInt())

        if (target == Mode.HIDDEN) {
            root.visibility = View.INVISIBLE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            setWindow(1, 1)
            stopTicking()
            return
        }
        root.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        pill.contentDescription = a?.let { "${it.title}. ${it.text ?: ""}" } ?: "Dynamic Island"

        when (target) {
            Mode.IDLE -> {
                compactRow.isVisible = false
                expandedHost.isVisible = false
                privacyDot.isVisible = privacy
                setBubble(null)
                applyLook(null, idle = true)
                // Grows out of the camera hole only when there's a camera/mic dot to show.
                if (privacy) animateTo(g.compactH * 2, g.compactH, pillRadius(g.compactH))
                else animateTo(g.idleW, g.idleH, g.idleH / 2f)
            }
            Mode.COMPACT -> {
                val act = a!!
                expandedHost.isVisible = false
                compactRow.isVisible = true
                privacyDot.isVisible = privacy
                compactLeft.setPadding((g.compactH * 0.18f).roundToInt(), 0, 0, 0)
                compactRight.setPadding(0, 0, if (privacy) dp(22) else dp(12), 0)
                binder.bindCompact(act, compactLeft, compactRight, (g.compactH * 0.74f).roundToInt())
                val wide = act.kind in setOf(Kind.MESSAGE, Kind.NOTE, Kind.NAVIGATION, Kind.GENERIC)
                val extra = (g.compactH * (if (wide) 5.2f else 3.2f) * prefs.widthScale).roundToInt()
                applyLook(act)
                setBubble(secondary)
                animateTo((g.compactW + extra).coerceAtMost(g.screenW - dp(24)), g.compactH, pillRadius(g.compactH))
                if (swap) fadeIn(compactRow)
            }
            Mode.EXPANDED -> {
                compactRow.isVisible = false
                privacyDot.isVisible = false
                setBubble(null)
                expandedHost.isVisible = true
                expandedHost.removeAllViews()
                val topPad = g.compactH + dp(2)
                val content = if (expandedId == TOOLS_ID) {
                    val ids = prefs.tools
                    val pages = ToolLayout.pages(ids, prefs.toolsPerPage)
                    toolsPage = toolsPage.coerceIn(0, pages.size - 1)
                    binder.buildTools(topPad, pages[toolsPage].mapNotNull { toolCell(it) }, toolsPage, pages.size, input)
                } else if (expandedId == INBOX_ID) {
                    binder.buildInbox(topPad, inboxCache, inboxUnread, isLocked())
                } else {
                    binder.buildExpanded(a!!, topPad, if (inputFor == a.id) input else null)
                }
                expandedHost.addView(content)
                content.measure(
                    View.MeasureSpec.makeMeasureSpec(g.expandedW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(g.screenH / 2, View.MeasureSpec.AT_MOST),
                )
                applyLook(a)
                animateTo(g.expandedW, max(content.measuredHeight, g.compactH * 2), dp(8) + dp(26) * prefs.roundness)
                if (swap) fadeIn(expandedHost)
            }
            Mode.HIDDEN -> Unit
        }
        if (tickers.isNotEmpty()) startTicking() else stopTicking()
    }

    private fun setBubble(a: IslandActivity?) {
        val size = geo.compactH
        if (a == null) {
            bubble.isVisible = false; bubbleW = 0
            (leftSpacer.layoutParams as LinearLayout.LayoutParams).width = 0
        } else {
            bubble.removeAllViews()
            val inner = size - dp(10)
            bubble.addView(binder.bubbleIcon(a, inner), FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
            bubble.contentDescription = "Switch to ${a.title}"
            bubble.isVisible = true
            (bubble.layoutParams as LinearLayout.LayoutParams).apply { width = size; height = size }
            bubbleW = size + dp(6)
            (leftSpacer.layoutParams as LinearLayout.LayoutParams).width = bubbleW
        }
        leftSpacer.requestLayout()
    }

    // ------------------------------------------------------------------ animation & window

    private fun pillRadius(h: Int) = h / 2f * prefs.roundness

    /** Colour, transparency and optional accent outline chosen in settings. */
    override val palette get() = Themes.byId(prefs.theme)

    private fun applyLook(a: IslandActivity?, idle: Boolean = false) {
        if (idle) {
            // Must match the camera hole exactly: pure opaque black (or red while calibrating).
            pillBg.setColor(if (prefs.calibrate) 0xCCFF3B30.toInt() else Color.BLACK)
            pillBg.setStroke(0, Color.TRANSPARENT)
            return
        }
        val p = palette
        val alpha = (255 * prefs.opacity * p.bgAlpha).roundToInt().coerceIn(40, 255)
        val col = (alpha shl 24) or (p.bg and 0x00FFFFFF)
        pillBg.setColor(col)
        when {
            prefs.outline -> pillBg.setStroke(dp(1.5f), ((a?.accent ?: p.fg) and 0x00FFFFFF) or (0x99 shl 24))
            p.stroke != 0 -> pillBg.setStroke(dp(1f), p.stroke)
            else -> pillBg.setStroke(0, Color.TRANSPARENT)
        }
        (bubble.background as? GradientDrawable)?.setColor(col)
    }

    private fun applyPill(w: Int, h: Int, r: Float) {
        val lp = pill.layoutParams ?: LinearLayout.LayoutParams(w, h)
        lp.width = w; lp.height = h
        (lp as? LinearLayout.LayoutParams)?.topMargin = max(0, (geo.compactH - h) / 2)
        pill.layoutParams = lp
        pillBg.cornerRadius = r
    }

    private fun animateTo(w: Int, h: Int, r: Float) {
        animator?.cancel()
        val fw = curW; val fh = curH; val fr = curR
        if (fw == w && fh == h) {
            applyPill(w, h, r); curR = r
            setWindow(w, h)
            return
        }
        val slack = dp(24)
        setWindow(max(fw, w) + slack, max(fh, h) + slack)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (560 / prefs.animSpeed).roundToInt().toLong()
            interpolator = SpringInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                curW = (fw + (w - fw) * t).roundToInt().coerceAtLeast(1)
                curH = (fh + (h - fh) * t).roundToInt().coerceAtLeast(1)
                curR = fr + (r - fr) * t
                applyPill(curW, curH, curR)
            }
            doOnEnd {
                curW = w; curH = h; curR = r
                applyPill(w, h, r)
                setWindow(w, h)
            }
            start()
        }
    }

    private fun setWindow(pillW: Int, pillH: Int) {
        if (!attached) return
        val w = pillW + bubbleW * 2
        params.width = w
        params.height = max(pillH, geo.compactH)
        params.x = geo.cx - w / 2 + dp(prefs.offsetX)
        params.y = geo.top + dp(prefs.offsetY)
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun fadeIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setStartDelay(140).setDuration(220).start()
    }

    private fun startTicking() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTicking() = handler.removeCallbacks(tickRunnable)

    // ------------------------------------------------------------------ gestures

    private val gestures = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when (mode) {
                Mode.IDLE -> if (dismissed.isNotEmpty()) { dismissed.clear(); haptic(); refresh() } else bounce()
                Mode.COMPACT -> primary?.let {
                    // "Preview first": show the full content in the island; tap again (or Open) to go to the app.
                    if (prefs.tapPreview && it.kind != Kind.TOOL) { haptic(); expand(it.id) } else open(it)
                }
                Mode.EXPANDED -> {
                    val id = expandedId
                    val a = if (id != null && !isPanel(id)) IslandRepository.get(id) else null
                    if (a != null && input == null) open(a) else if (input == null) collapse()
                }
                Mode.HIDDEN -> Unit
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            haptic()
            when (mode) {
                Mode.IDLE -> expand(TOOLS_ID)
                Mode.COMPACT -> primary?.let { expand(it.id) }
                Mode.EXPANDED -> if (input == null) collapse()
                Mode.HIDDEN -> Unit
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY > 0) { // swipe down = expand
                    when (mode) {
                        Mode.COMPACT -> primary?.let { expand(it.id) }
                        Mode.IDLE -> if (prefs.inboxEnabled) openInboxPanel() else expand(TOOLS_ID)
                        else -> Unit
                    }
                } else { // swipe up = collapse / dismiss
                    when (mode) {
                        Mode.EXPANDED -> if (input == null) collapse()
                        Mode.COMPACT -> primary?.let { dismissed[it.id] = it.contentHash; haptic(); refresh() }
                        else -> Unit
                    }
                }
            } else { // swipe sideways = dismiss the animation back to the pill (activity keeps running, like iOS)
                when (mode) {
                    Mode.EXPANDED -> if (expandedId == TOOLS_ID && input == null) {
                        // Swipe between tool pages.
                        val pages = ToolLayout.pages(prefs.tools, prefs.toolsPerPage).size
                        if (pages > 1) { toolsPage = (toolsPage + (if (velocityX < 0) 1 else pages - 1)) % pages; haptic(); rescheduleCollapse(); lastKey = ""; refresh() }
                        else collapse()
                    } else if (input == null) collapse()
                    Mode.COMPACT -> primary?.let { dismissed[it.id] = it.contentHash; haptic(); refresh() }
                    else -> Unit
                }
            }
            return true
        }
    }

    private fun bounce() {
        pill.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110).withEndAction {
            pill.animate().scaleX(1f).scaleY(1f).setInterpolator(SpringInterpolator()).setDuration(400).start()
        }.start()
    }

    private fun expand(id: String) {
        expandedId = id
        stickyPeekId = null
        rescheduleCollapse()
        refresh()
    }

    private fun rescheduleCollapse(ms: Long = 10_000) {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, ms)
    }

    fun collapse() {
        handler.removeCallbacks(collapseRunnable)
        val wasInput = input != null
        expandedId = null
        stickyPeekId = null
        if (wasInput) endInput()
        refresh(force = true)
    }

    fun onPeek(id: String) {
        IslandDebug.lastPeek = "$id (blocked)"
        if (!prefs.peek || input != null) return
        val a = IslandRepository.get(id) ?: return
        if (!isVisible(a) || hiddenByEnvironment() && a.kind != Kind.CALL) return
        IslandDebug.lastPeek = "$id ${a.kind}" 
        // Don't steal the island from something the user opened themselves (except calls).
        if (expandedId != null && expandedId != id && stickyPeekId == null && a.kind != Kind.CALL) return
        expandedId = id
        haptic()
        handler.removeCallbacks(collapseRunnable)
        if (a.peekMs > 0) {
            stickyPeekId = null
            handler.postDelayed(collapseRunnable, a.peekMs)
        } else {
            stickyPeekId = id
        }
        refresh()
    }

    private fun haptic() {
        if (prefs.haptics) IslandTools.tick(ctx)
    }

    // ------------------------------------------------------------------ ContentBinder.Host

    override fun hideContent(a: IslandActivity): Boolean {
        if (!prefs.privacyLock || (a.kind != Kind.MESSAGE && a.kind != Kind.GENERIC)) return false
        return ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    override fun open(a: IslandActivity) {
        haptic()
        when {
            a.onTap != null -> a.onTap.invoke()
            a.contentIntent == null && (a.packageName == ctx.packageName || a.id.startsWith("mod:")) -> { expand(a.id); return }
            else -> launch(a.contentIntent, a.packageName)
        }
        if (mode == Mode.EXPANDED) collapse()
        if (a.kind == Kind.MESSAGE || a.kind == Kind.GENERIC) IslandRepository.remove(a.id)
    }

    override fun perform(a: IslandActivity, act: IslandAction) {
        haptic()
        when {
            act.onInput != null -> {
                val replyTo = if (a.kind == Kind.MESSAGE && !a.text.isNullOrBlank()) a.title.toString() to a.text.toString() else null
                startInput(a.id, act.inputHint ?: "Reply…", replyTo, act.onInput); return
            }
            act.onClick != null -> act.onClick.invoke()
            act.intent != null -> sendPending(act.intent)
        }
        if (a.kind == Kind.MESSAGE) IslandRepository.remove(a.id)
        collapse()
    }

    override fun media(a: IslandActivity, cmd: ContentBinder.MediaCmd) {
        haptic()
        val c = a.media ?: return
        val tc = c.transportControls
        when (cmd) {
            ContentBinder.MediaCmd.PREV -> tc.skipToPrevious()
            ContentBinder.MediaCmd.NEXT -> tc.skipToNext()
            ContentBinder.MediaCmd.TOGGLE -> if (a.isPlaying) tc.pause() else tc.play()
        }
        rescheduleCollapse()
    }

    /** Resolve a saved tool id into what the tools grid shows. */
    private fun toolCell(id: String): ContentBinder.ToolCell? {
        if (id.startsWith(ModuleRegistry.TOOL_PREFIX)) {
            val t = ModuleRegistry.tool(id) ?: return null // module not installed / approved / connected
            return ContentBinder.ToolCell(id, t.label, t.icon?.constantState?.newDrawable()?.mutate() ?: t.icon, tint = true, active = false)
        }
        if (ToolCatalog.isApp(id)) {
            val pkg = ToolCatalog.appPackage(id)
            val icon = NotificationParser.appIcon(ctx, pkg) ?: return null
            return ContentBinder.ToolCell(id, NotificationParser.appLabel(ctx, pkg), icon, tint = false, active = false)
        }
        val d = ToolCatalog.def(id) ?: return null
        val active = when (id) {
            "torch" -> IslandRepository.torchOn.value
            "dnd" -> ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter.let { it != null && it != NotificationManager.INTERRUPTION_FILTER_ALL }
            else -> false
        }
        val label = if (active && d.activeLabel != null) d.activeLabel else d.label
        return ContentBinder.ToolCell(id, label, ContextCompat.getDrawable(ctx, d.icon), tint = true, active = active)
    }

    override fun tool(id: String) {
        haptic()
        fun global(action: Int) { collapse(); handler.postDelayed({ globalAction(action) }, 350) }
        when {
            id == "torch" -> { IslandTools.toggleTorch(ctx); handler.postDelayed({ collapse() }, 250) }
            id == "timer:custom" -> startInput(TOOLS_ID, "Minutes (e.g. 12)…") { txt ->
                txt.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { IslandTools.startTimer(ctx, (it * 60_000).toLong()) }
            }
            id.startsWith(ToolCatalog.TIMER_PREFIX) -> {
                id.removePrefix(ToolCatalog.TIMER_PREFIX).toLongOrNull()?.let { IslandTools.startTimer(ctx, it * 60_000) }
                collapse()
            }
            id == "stopwatch" -> { IslandTools.startStopwatch(ctx); collapse() }
            id == "screenshot" -> { collapse(); handler.postDelayed({ globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 450) }
            id == "note" -> startInput(TOOLS_ID, "Pin a note to the island…") { IslandTools.pinNote(ctx, it) }
            id == "inbox" -> openInboxPanel()
            id == "otp" -> copyLatestOtp()
            id == "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            id == "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            id == "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            id == "split" -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            id == "shade" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            id == "qs" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            id == "play" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            id == "next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            id == "prev" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            id == "wifi" -> { collapse(); launchIntent(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
            id == "bt" -> { collapse(); launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            id == "nfc" -> { collapse(); launchIntent(Intent(Settings.Panel.ACTION_NFC)) }
            id == "volume" -> { collapse(); launchIntent(Intent(Settings.Panel.ACTION_VOLUME)) }
            id == "ringer" -> cycleRinger()
            id == "dnd" -> toggleDnd()
            id.startsWith(ModuleRegistry.TOOL_PREFIX) -> { collapse(); ModuleRegistry.toolTapped(id) }
            ToolCatalog.isApp(id) -> {
                collapse()
                ctx.packageManager.getLaunchIntentForPackage(ToolCatalog.appPackage(id))?.let { launchIntent(it) }
            }
        }
    }

    private fun mediaKey(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        rescheduleCollapse(3000)
    }

    private fun cycleRinger() {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> if (nm?.isNotificationPolicyAccessGranted == true) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am.ringerMode = next } catch (_: Exception) {}
        collapse()
    }

    private fun toggleDnd() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        collapse()
        if (!nm.isNotificationPolicyAccessGranted) {
            flash("Allow Do Not Disturb access", "Find Dynamic Island in the list and switch it on")
            launchIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    private fun copyLatestOtp() {
        Thread {
            val otp = InboxStore.latestOtp(ctx)
            handler.post {
                collapse()
                if (otp != null) {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OTP", otp))
                    flash("OTP $otp copied", "Paste it where you need it")
                } else flash("No recent OTP", "Nothing in the last 15 minutes")
            }
        }.start()
    }

    /** A short system-style glimpse in the island. */
    private fun flash(title: String, text: String) {
        val now = System.currentTimeMillis()
        IslandRepository.upsert(
            IslandActivity(
                id = "sys", kind = Kind.SYSTEM, packageName = ctx.packageName, title = title, text = text,
                icon = ContextCompat.getDrawable(ctx, R.drawable.ic_key), tintIcon = true, accent = Colors.GREEN,
                compactText = title, expiresAt = now + 2600, contentHash = now.toInt(),
            )
        )
    }

    // ------------------------------------------------------------------ inbox panel

    private fun openInboxPanel() {
        loadInbox()
        expand(INBOX_ID)
    }

    private fun loadInbox() {
        Thread {
            val list = InboxStore.query(ctx, limit = 60).distinctBy { it.pkg + "|" + it.title }.take(5)
            val unread = InboxStore.unreadCount(ctx)
            handler.post {
                inboxCache = list; inboxUnread = unread
                if (expandedId == INBOX_ID) { lastKey = ""; refresh() }
            }
        }.start()
    }

    /** Called when the inbox changes (new notification, cleared…). */
    fun onInboxChanged() { if (expandedId == INBOX_ID && input == null) loadInbox() }

    override fun openInbox(e: InboxEntry) {
        haptic()
        InboxStore.markRead(ctx, e.id)
        collapse()
        val pi = InboxStore.liveIntents[e.key]
        if (pi != null) launch(pi, e.pkg) else ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.let { launchIntent(it) }
    }

    override fun inboxAction(action: String) {
        haptic()
        when (action) {
            "clear" -> { InboxStore.clearAll(ctx); inboxCache = emptyList(); inboxUnread = 0; lastKey = ""; refresh(force = true) }
            "all" -> { collapse(); launchIntent(Intent(ctx, com.ajit.dynamicisland.InboxActivity::class.java)) }
        }
    }

    private fun isLocked() = prefs.privacyLock && ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Start an activity from the overlay (works with Android 14+ background-start rules). */
    private fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, intent.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!sendPending(pi)) try { ctx.startActivity(intent) } catch (_: Exception) {}
    }

    override fun addTicker(t: () -> Unit) { tickers += t }

    override fun submitInput(text: String) {
        val i = input ?: return
        if (text.isNotBlank()) i.onSend(text.trim())
        endInput()
        if (inputFor != TOOLS_ID) primary?.takeIf { it.kind == Kind.MESSAGE }?.let { IslandRepository.remove(it.id) }
        collapse()
    }

    override fun cancelInput() {
        endInput()
        collapse()
    }

    private fun startInput(forId: String, hint: String, replyTo: Pair<String, String>? = null, onSend: (String) -> Unit) {
        handler.removeCallbacks(collapseRunnable)
        input = ContentBinder.InputState(hint, onSend, replyTo)
        inputFor = forId
        expandedId = forId
        stickyPeekId = null
        // Make the window focusable so the keyboard can type into it.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        refresh(force = true)
        val et = input?.editText ?: return
        handler.postDelayed({
            et.requestFocus()
            ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    // ---- Smart replies (optional, on-device AI Engine) ----

    private var ai: com.ajit.dynamicisland.sdk.ai.AiClient? = null

    override val smartReplies: Boolean
        get() = prefs.smartReplies && com.ajit.dynamicisland.sdk.ai.AiClient.isTrusted(ctx)

    override fun suggestReplies(input: ContentBinder.InputState) {
        val (from, msg) = input.replyTo ?: return
        if (input.loading) return
        input.loading = true; input.suggestions = null
        binder.fillReplyChips(input, primary?.accent ?: com.ajit.dynamicisland.core.Colors.BLUE)
        val client = ai ?: com.ajit.dynamicisland.sdk.ai.AiClient(ctx).also { ai = it }
        input.requestId = client.generate(
            system = com.ajit.dynamicisland.core.ai.SmartInbox.REPLY_SYSTEM,
            prompt = com.ajit.dynamicisland.core.ai.SmartInbox.replyPrompt(from, msg),
            maxTokens = com.ajit.dynamicisland.core.ai.SmartInbox.REPLY_TOKENS, temperature = 0.7f,
            onDone = { text ->
                input.loading = false; input.suggestions = com.ajit.dynamicisland.core.ai.SmartInbox.parseReplies(text)
                if (this.input === input) binder.fillReplyChips(input, primary?.accent ?: com.ajit.dynamicisland.core.Colors.BLUE)
            },
            onError = { _, msg2 ->
                input.loading = false; input.suggestions = emptyList()
                if (this.input === input) { binder.fillReplyChips(input, primary?.accent ?: com.ajit.dynamicisland.core.Colors.BLUE); android.widget.Toast.makeText(ctx, msg2, android.widget.Toast.LENGTH_SHORT).show() }
            },
        )
        // Keep the island open while the model thinks.
        handler.removeCallbacks(collapseRunnable)
    }

    private fun endInput() {
        input?.requestId?.let { id -> ai?.cancel(id) }
        input?.editText?.let { et ->
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(et.windowToken, 0)
        }
        input = null
        inputFor = null
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ launching

    private fun bgStartOptions() = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }.toBundle()

    private fun sendPending(pi: PendingIntent): Boolean = try {
        pi.send(ctx, 0, null, null, null, null, bgStartOptions())
        true
    } catch (e: Exception) {
        false
    }

    private fun launch(pi: PendingIntent?, pkg: String) {
        if (pi != null && sendPending(pi)) return
        try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
        } catch (_: Exception) {
        }
    }
}
