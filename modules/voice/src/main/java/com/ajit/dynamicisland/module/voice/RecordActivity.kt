package com.ajit.dynamicisland.module.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Invisible screen opened by the island's "Record" tool. Starting the microphone needs a visible
 * activity, so this starts (or stops) the recording and closes straight away.
 */
class RecordActivity : AppCompatActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)) go()
        else { Toast.makeText(this, "Meeting Notes needs the microphone to record", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (RecorderService.state) {
            RecorderService.State.RECORDING, RecorderService.State.PAUSED, RecorderService.State.STARTING -> {
                RecorderService.command(this, RecorderService.ACTION_STOP); finish(); return
            }
            RecorderService.State.SAVING -> { finish(); return }
            RecorderService.State.IDLE -> {}
        }
        if (!ModelStore.isPresent(this)) {
            Toast.makeText(this, "Download the speech model first (one time)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        val need = neededPermissions().filterNot(::has)
        if (need.isEmpty()) go() else ask.launch(need.toTypedArray())
    }

    private fun go() {
        try { RecorderService.start(this) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
