package com.ajit.dynamicisland.module.screen

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** The Screen Recorder screen, for review and the docs. Run with -Pscreenshots=record. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun shoot(name: String, night: Boolean = false, setup: (MainActivity) -> Unit = {}) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            sc.onActivity(setup)
            repeat(4) { Thread.sleep(60); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "$name.png").path, opts) }
        }
    }

    @Test fun main() {
        Prefs.setAudio(ctx, Audio.BOTH); Prefs.setQuality(ctx, Quality.HD1080); Prefs.setFps(ctx, 60); Prefs.setCountdown(ctx, true)
        shoot("screen-recorder-1")
    }

    @Test fun soundChoiceDark() {
        Prefs.setAudio(ctx, Audio.PHONE)
        shoot("screen-recorder-2-sound-dark", night = true) { a ->
            a.javaClass.getDeclaredMethod("choose", String::class.java).apply { isAccessible = true }.invoke(a, "sound")
        }
    }
}
