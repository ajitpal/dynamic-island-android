package com.ajit.dynamicisland.shots

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ajit.dynamicisland.MainActivity
import com.ajit.dynamicisland.OnboardingActivity
import com.ajit.dynamicisland.PageActivity
import com.ajit.dynamicisland.core.Demo
import com.ajit.dynamicisland.core.IslandAction
import com.ajit.dynamicisland.core.IslandActivity
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.ui.ContentBinder
import com.ajit.dynamicisland.ui.Palette
import com.ajit.dynamicisland.ui.Themes
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the app's screens and the island's states with sample data, for the README and website.
 * Run with -Pscreenshots=record to update docs/screenshots/app, -Pscreenshots=verify in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))
    private fun out(name: String) = File(dir, "$name.png").path

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs(ctx).set(OnboardingActivity.PREF_DONE, true)
        Prefs(ctx).note = "Gate B12 · Boarding 18:40"
        IslandRepository.removeWhere { true }
    }

    private fun settle() { repeat(3) { Thread.sleep(80); ShadowLooper.idleMainLooper() } }

    private fun shoot(intent: Intent, name: String, night: Boolean = false) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        ActivityScenario.launch<android.app.Activity>(intent).use { sc ->
            settle()
            sc.onActivity { it.window.decorView.captureRoboImage(out(name), opts) }
        }
    }

    private fun main(tab: Int) = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
    private fun onboarding(step: Int) = Intent(ApplicationProvider.getApplicationContext(), OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, step)
    private fun page(key: String) = Intent(ApplicationProvider.getApplicationContext(), PageActivity::class.java).putExtra(PageActivity.EXTRA_KEY, key)

    private fun sampleModules() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun d(res: Int) = androidx.core.content.ContextCompat.getDrawable(ctx, res)
        fun icon(color: Int, res: Int) = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = 22f },
            d(res)!!.mutate().apply { setTint(android.graphics.Color.WHITE) })).apply { setLayerInset(1, 18, 18, 18, 18) }
        val cn = android.content.ComponentName("x", "y")
        val voice = com.ajit.dynamicisland.core.modules.ModuleInfo("com.ajit.dynamicisland.module.voice", cn, "Meeting Notes",
            "Record meetings from the island and get an on-device transcript, AI notes and answers.", "Ajit Kumar Pal", cn,
            com.ajit.dynamicisland.core.modules.Trust.SAME_DEVELOPER, "12178c6a", true, true, emptyList(), "1.3.0",
            icon(0xFFFF3B30.toInt(), com.ajit.dynamicisland.R.drawable.ic_record), "x", true, true)
        val ai = voice.copy(pkg = "com.ajit.dynamicisland.module.ai", label = "AI Engine", version = "1.2.0",
            description = "A small language model on your phone for notes, Catch me up and reply suggestions.",
            icon = icon(0xFF5856D6.toInt(), com.ajit.dynamicisland.R.drawable.ic_spark))
        val tools = listOf(
            com.ajit.dynamicisland.core.modules.ModuleTool("mod:v/record", voice.pkg, "record", "Record meeting", d(com.ajit.dynamicisland.R.drawable.ic_record)),
            com.ajit.dynamicisland.core.modules.ModuleTool("mod:v/recordings", voice.pkg, "recordings", "Recordings", null, "x"),
            com.ajit.dynamicisland.core.modules.ModuleTool("mod:v/ask", voice.pkg, "ask", "Ask last meeting", null),
            com.ajit.dynamicisland.core.modules.ModuleTool("mod:a/open", ai.pkg, "open", "AI Engine", null, "x"),
            com.ajit.dynamicisland.core.modules.ModuleTool("mod:a/try", ai.pkg, "try", "Try AI", null),
        )
        val tiles = listOf(
            com.ajit.dynamicisland.core.modules.ModuleTile(voice.pkg, "Weekly product sync", "2 hours ago · notes ready · 3 action items", voice.icon, {}, listOf("Record" to {}, "Recordings" to {})),
            com.ajit.dynamicisland.core.modules.ModuleTile(ai.pkg, "AI Engine", "Qwen3 1.7B ready · works offline", ai.icon, {}, listOf("Open" to {})),
        )
        com.ajit.dynamicisland.core.modules.ModuleRegistry.showPreview(listOf(voice, ai), tools, tiles)
    }

    @Test fun appScreens() {
        sampleModules()
        Demo.message(ApplicationProvider.getApplicationContext())
        shoot(main(MainActivity.TAB_ISLAND), "app-01-island")
        shoot(main(MainActivity.TAB_MODULES), "app-02-modules")
        shoot(main(MainActivity.TAB_SETTINGS), "app-03-settings")
        shoot(page(PageActivity.LOOK), "app-04-look")
        shoot(page(PageActivity.TOOLS), "app-05-tools")
        shoot(onboarding(0), "app-06-welcome")
        shoot(onboarding(1), "app-07-permissions")
        shoot(onboarding(2), "app-08-keep-running")
    }

    @Test fun appScreensDark() {
        sampleModules()
        shoot(main(MainActivity.TAB_MODULES), "app-10-modules-dark", night = true)
        shoot(main(MainActivity.TAB_SETTINGS), "app-09-settings-dark", night = true)
    }

    /** Settings once set up: the Dynamic Island on/off switch at the top. */
    @Test fun settingsWithSwitch() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val svc = android.content.ComponentName(ctx, com.ajit.dynamicisland.service.IslandAccessibilityService::class.java).flattenToString()
        val nls = android.content.ComponentName(ctx, com.ajit.dynamicisland.service.IslandNotificationListener::class.java).flattenToString()
        android.provider.Settings.Secure.putString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, svc)
        android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", nls)
        shoot(main(MainActivity.TAB_SETTINGS), "app-11-settings-switch")
    }

    /** Catch Me Up sheet in each state (Inbox). */
    @Test fun catchUpSheet() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(ctx, com.ajit.dynamicisland.R.style.Theme_Island)
        val hig = com.ajit.dynamicisland.ui.hig.Hig(themed)
        val now = android.os.SystemClock.elapsedRealtime()
        val summary = "## Needs attention\n- Priya asked if you can join dinner at 7 pm\n- HDFC: payment of Rs 2,499 to Swiggy\n- Amazon: parcel arrives today\n## Everything else\n- WhatsApp: 6 messages in Family\n- Gmail: 3 newsletters"
        fun render(state: com.ajit.dynamicisland.core.ai.CatchUp.State, name: String) {
            val sheet = com.ajit.dynamicisland.screens.CatchUpSheet(themed, hig, {}, {}, {}, {}, {}, {})
            sheet.render(state)
            val frame = FrameLayout(themed).apply { setBackgroundColor(0xFF8E8E93.toInt()); setPadding(0, 120, 0, 0) }
            frame.addView(sheet.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 786, 1500)
            val bmp = android.graphics.Bitmap.createBitmap(786, 1500, android.graphics.Bitmap.Config.ARGB_8888)
            frame.draw(android.graphics.Canvas(bmp))
            bmp.captureRoboImage(out(name), opts)
        }
        render(com.ajit.dynamicisland.core.ai.CatchUp.State.Working(com.ajit.dynamicisland.core.ai.CatchUp.Step.LOADING, 23, now - 12_000, model = "Qwen3 1.7B"), "catchup-1-loading")
        render(com.ajit.dynamicisland.core.ai.CatchUp.State.Working(com.ajit.dynamicisland.core.ai.CatchUp.Step.WRITING, 23, now - 41_000, model = "Qwen3 1.7B", text = summary.substringBefore("- Amazon")), "catchup-2-writing")
        render(com.ajit.dynamicisland.core.ai.CatchUp.State.Done(summary, 23, 1761640200000, "Qwen3 1.7B"), "catchup-3-done")
        render(com.ajit.dynamicisland.core.ai.CatchUp.State.Failed(com.ajit.dynamicisland.core.ai.CatchUp.Reason.BUSY, "AI Engine Is Busy",
            "AI Engine is busy with Meeting Notes. Try again when it's finished."), "catchup-4-busy")
    }

    /** The island's expanded states, drawn by the same code the overlay uses. */
    @Test fun islandStates() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(ctx, com.ajit.dynamicisland.R.style.Theme_Island_Overlay)
        val binder = ContentBinder(themed, FakeHost(Themes.byId("black")))
        fun render(a: IslandActivity, name: String) {
            val frame = FrameLayout(themed).apply { setBackgroundColor(0xFF1B2A4A.toInt()); setPadding(24, 24, 24, 24) }
            val card = binder.buildExpanded(a, 24, null).apply { background = com.ajit.dynamicisland.ui.rounded(Color.BLACK, 64f) }
            frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            frame.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            val bmp = android.graphics.Bitmap.createBitmap(frame.measuredWidth, frame.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            frame.draw(android.graphics.Canvas(bmp))
            bmp.captureRoboImage(out(name), opts)
        }
        Demo.message(ctx); Demo.incomingCall(ctx); Demo.payment(ctx); Demo.liveScore(ctx)
        settle()
        IslandRepository.get("demo:msg")?.let { render(it, "island-01-message") }
        IslandRepository.get("demo:call")?.let { render(it, "island-02-call") }
        IslandRepository.activities.value.values.firstOrNull { it.title.contains("Paid") }?.let { render(it, "island-03-payment") }
        IslandRepository.activities.value.values.firstOrNull { it.id.contains("score") || it.id.contains("live") }?.let { render(it, "island-04-live-score") }
    }

    /** 2.0: three at once. The compact island with the circle and "+1" badge, and the touch-and-hold list. */
    @Test fun threeAtOnce() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(ctx, com.ajit.dynamicisland.R.style.Theme_Island_Overlay)
        val binder = ContentBinder(themed, FakeHost(Themes.byId("black")))
        Demo.threeAtOnce(ctx)
        settle()
        val ordered = IslandRepository.activities.value.values.sortedWith(compareByDescending<IslandActivity> { it.priority }.thenByDescending { it.updatedAt })
        val stack = com.ajit.dynamicisland.core.IslandStack.arrange(ordered, null) { it.id }
        fun draw(v: View, name: String, w: Int) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val bmp = android.graphics.Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp)); bmp.captureRoboImage(out(name), opts)
        }
        // Compact: pill + circle with the badge (same binder calls as the overlay).
        val h = 88
        val row = LinearLayout(themed).apply { gravity = android.view.Gravity.CENTER; setBackgroundColor(0xFF1B2A4A.toInt()); setPadding(24, 36, 24, 36) }
        val pill = LinearLayout(themed).apply { gravity = android.view.Gravity.CENTER_VERTICAL; background = com.ajit.dynamicisland.ui.rounded(Color.BLACK, h / 2f); setPadding(18, 0, 24, 0) }
        val left = FrameLayout(themed); val right = FrameLayout(themed)
        binder.bindCompact(stack.primary!!, left, right, 64)
        pill.addView(left, LinearLayout.LayoutParams(0, h, 1f)); pill.addView(View(themed), LinearLayout.LayoutParams(70, 1)); pill.addView(right, LinearLayout.LayoutParams(0, h, 1f))
        row.addView(pill, LinearLayout.LayoutParams(470, h))
        val bubble = FrameLayout(themed)
        val circle = FrameLayout(themed).apply { background = com.ajit.dynamicisland.ui.circle(Color.BLACK) }
        circle.addView(binder.bubbleIcon(stack.bubble!!, h - 26), FrameLayout.LayoutParams(h - 26, h - 26, android.view.Gravity.CENTER))
        bubble.addView(circle, FrameLayout.LayoutParams(h, h))
        bubble.addView(android.widget.TextView(themed).apply {
            text = com.ajit.dynamicisland.core.IslandStack.badge(stack.hidden); setTextColor(Color.WHITE); textSize = 9.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER; setPadding(8, 0, 8, 0); minWidth = 38
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0A84FF.toInt()); cornerRadius = 999f; setStroke(4, Color.BLACK) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 38, android.view.Gravity.TOP or android.view.Gravity.END))
        row.addView(bubble, LinearLayout.LayoutParams(h, h).apply { marginStart = 16 })
        draw(row, "island-05-three-at-once", 786)
        // Touch and hold the circle: all of them.
        val frame = FrameLayout(themed).apply { setBackgroundColor(0xFF1B2A4A.toInt()); setPadding(24, 24, 24, 24) }
        frame.addView(binder.buildStack(24, stack.all, stack.primary?.id, stack.count).apply { background = com.ajit.dynamicisland.ui.rounded(Color.BLACK, 64f) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        draw(frame, "island-06-all-activities", 786)
    }

    private class FakeHost(override val palette: Palette) : ContentBinder.Host {
        override fun hideContent(a: IslandActivity) = false
        override fun open(a: IslandActivity) {}
        override fun perform(a: IslandActivity, act: IslandAction) {}
        override fun media(a: IslandActivity, cmd: ContentBinder.MediaCmd) {}
        override fun tool(id: String) {}
        override fun openInbox(e: com.ajit.dynamicisland.core.inbox.InboxEntry) {}
        override fun inboxAction(action: String) {}
        override fun addTicker(t: () -> Unit) {}
        override fun submitInput(text: String) {}
        override fun cancelInput() {}
        override val smartReplies = false
        override fun suggestReplies(input: ContentBinder.InputState) {}
    }

    @Suppress("unused") private val keep = LinearLayout::class
}
