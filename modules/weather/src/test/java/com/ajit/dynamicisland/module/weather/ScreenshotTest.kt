package com.ajit.dynamicisland.module.weather

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

/** The Weather screen with saved sample data (a public city, no real location), for review and the docs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val opts = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f))
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    private fun shoot(name: String, night: Boolean = false) {
        if (night) RuntimeEnvironment.setQualifiers("+night")
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java)).use { sc ->
            repeat(4) { Thread.sleep(60); ShadowLooper.idleMainLooper() }
            sc.onActivity { it.window.decorView.captureRoboImage(File(dir, "$name.png").path, opts) }
        }
    }

    @Test fun weather() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        WPrefs.setPlace(ctx, "Mumbai", 19.08, 72.88, fromLocation = false)
        WPrefs.setFahrenheit(ctx, false)
        WPrefs.setCached(ctx, res("forecast.json"), res("air.json"))
        Weather.nowForTest = 1793440800L
        shoot("weather-1")
    }

    @Test fun setupDark() = shoot("weather-2-setup-dark", night = true)
}
