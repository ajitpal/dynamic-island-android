package com.ajit.dynamicisland.module.speech

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

/** Speech Pack's screen, for review and the docs. Run with -Pscreenshots=record. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1500dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))

    @Test fun speechPack() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Models.select(ctx, Model.STANDARD)
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            repeat(3) { Thread.sleep(80); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "speech-pack.png").path, opts) }
        }
    }
}
