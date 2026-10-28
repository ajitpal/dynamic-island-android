package com.ajit.dynamicisland.module.ai

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

/** The AI Engine screen, for review and the docs. Run with -Pscreenshots=record. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1600dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))

    @Test fun engineScreen() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Diag.init(ctx)
        // Fixed lines (not Diag.record, which stamps the current time) so the image is the same on every run.
        ctx.getSharedPreferences("diag", android.content.Context.MODE_PRIVATE).edit().putString("runs",
            "10-28 09:02:11 · Qwen3 1.7B · The model didn't start answering within 180 s. Try a smaller model or a shorter request.\n" +
            "10-28 09:05:40 · Qwen3 1.7B · ok · load 13.0s · first word 9.3s · total 38.1s · ~8 tokens/s").commit()
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            repeat(3) { Thread.sleep(80); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "ai-engine.png").path, opts) }
        }
    }
}
