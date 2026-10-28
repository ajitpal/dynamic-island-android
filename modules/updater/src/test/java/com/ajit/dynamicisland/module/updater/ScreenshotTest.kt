package com.ajit.dynamicisland.module.updater

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

/** The Software Update screen in each state, for review and the docs. Run with -Pscreenshots=record. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val notes = "- **Catch Me Up shows its work:** steps with checkmarks and a timer\n- **One AI request at a time**, with a clear message when busy\n- **On/off switch** and a Quick Settings tile"
    private fun app(pkg: String, name: String, code: Long, ver: String, size: Long) =
        Release.App(pkg, name, code, ver, "x.apk", "a".repeat(64), size)
    private val release = Release("1.10.0", "v1.10.0", notes, emptyList())

    private fun shoot(state: Updater.State, name: String, night: Boolean = false) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        UPrefs.setLastCheck(ctx, System.currentTimeMillis())   // the screen won't start a real check
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            Updater.showForTest(state)
            repeat(3) { Thread.sleep(60); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "$name.png").path, opts) }
        }
        Updater.resetForTest()
    }

    @Test fun available() {
        val items = listOf(
            Updater.Item(Update(app("com.ajit.dynamicisland.module.voice", "Meeting Notes", 5, "1.4.0", 12_400_000), "1.3.0", 4), Updater.Phase.DONE, 1f),
            Updater.Item(Update(app("com.ajit.dynamicisland.module.ai", "AI Engine", 5, "1.3.0", 14_600_000), "1.2.1", 4), Updater.Phase.DOWNLOADING, 0.45f),
            Updater.Item(Update(app("com.ajit.dynamicisland", "Dynamic Island", 17, "1.10.0", 2_700_000), "1.9.2", 16)),
        )
        shoot(Updater.State.Available(release, items, System.currentTimeMillis() - 60_000), "updater-1-available")
    }

    @Test fun upToDate() = shoot(Updater.State.UpToDate(System.currentTimeMillis() - 3 * 3600_000L, "1.10.0"), "updater-2-up-to-date")

    @Test fun failedAndDark() {
        val items = listOf(Updater.Item(Update(app("com.ajit.dynamicisland", "Dynamic Island", 17, "1.10.0", 2_700_000), "1.9.2", 16),
            Updater.Phase.FAILED, canRetry = false, error = "The installed Dynamic Island is signed with a different key (for example a test build), so it can't be updated here."))
        shoot(Updater.State.Available(release, items, System.currentTimeMillis()), "updater-3-failed-dark", night = true)
    }
}
