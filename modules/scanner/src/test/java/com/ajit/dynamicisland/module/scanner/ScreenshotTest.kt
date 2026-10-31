package com.ajit.dynamicisland.module.scanner

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

/** The Scanner screen with sample scans (made-up, no real data), for review and the docs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))

    @Test fun scanner() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        History.clear(ctx)
        History.add(ctx, "BEGIN:VCARD\nFN:Asha Sharma\nTEL:+91 98765 43210\nEND:VCARD", "QR_CODE")
        History.add(ctx, "WIFI:T:WPA;S:Cafe Guest;P:sample-password;;", "QR_CODE")
        History.add(ctx, "upi://pay?pa=corner.store@okaxis&pn=Corner%20Store&am=149.50", "QR_CODE")
        val last = History.add(ctx, "https://www.example.com/menu", "QR_CODE")
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW, last.id)).use { sc ->
            repeat(4) { Thread.sleep(60); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "scanner-1.png").path, opts) }
        }
    }
}
