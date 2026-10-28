package com.ajit.dynamicisland.module.voice

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Meeting Notes' main screen, for review and the docs. Run with -Pscreenshots=record. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1700dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))

    @Test fun hindiNeedsSetup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        VoicePrefs.setLanguage(ctx, Lang.HINGLISH)
        // A fixed saved meeting so the list isn't empty (fixed date: no "x minutes ago" drift).
        Recordings.save(ctx, Recording("1761600000000", "Weekly product sync", 1761600000000, 41 * 60_000L,
            listOf(Line(0, "मैं कल तक रिपोर्ट भेज दूंगा।"), Line(4000, "Rahul ko presentation ready karni hai")), "speechpack", "hinglish"))
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            repeat(4) { Thread.sleep(80); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "meeting-notes-hindi.png").path, opts) }
        }
    }
}
