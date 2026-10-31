package com.ajit.dynamicisland.module.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible screen behind "Record screen" (island tool, tile, app). If a recording is running it
 * stops it. Otherwise it asks for what's needed (notifications; microphone only if sound is on), then
 * Android's own "Start recording?" prompt, which Android 14+ shows every time, and hands the answer to
 * [RecorderService]. Only the island and this app can open it.
 */
class CaptureActivity : AppCompatActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            try { RecorderService.start(this, res.resultCode, data) } catch (e: Exception) {
                Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Prefs.audio(this).needsPermission && !has(Manifest.permission.RECORD_AUDIO)) {
            // Record without sound rather than not at all, and say so.
            Toast.makeText(this, "No microphone access, so this recording has no sound", Toast.LENGTH_LONG).show()
            RecorderService.noSoundOnce = true
        }
        askConsent()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (RecorderService.active) { RecorderService.command(this, RecorderService.ACTION_STOP); finish(); return }
        val need = needed(this).filterNot(::has)
        if (need.isEmpty()) askConsent() else ask.launch(need.toTypedArray())
    }

    private fun askConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try { consent.launch(mpm.createScreenCaptureIntent()) } catch (e: Exception) {
            Toast.makeText(this, "This phone can't record the screen", Toast.LENGTH_LONG).show(); finish()
        }
    }

    companion object {
        fun needed(c: Context): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Prefs.audio(c).needsPermission) add(Manifest.permission.RECORD_AUDIO)
        }
    }
}
