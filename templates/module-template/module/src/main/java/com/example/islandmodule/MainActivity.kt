package com.example.islandmodule

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.sdk.HostVerifier
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.google.android.material.button.MaterialButton

/** Your module's own screen: settings, permissions, status. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 28f })
        col.addView(TextView(this).apply {
            text = when {
                !HostVerifier.isHostInstalled(this@MainActivity) -> "Install Dynamic Island first."
                IslandModuleService.current?.island?.isConnected == true -> "✓ Connected to the island"
                else -> "Turn this module on in Dynamic Island → Modules."
            }
            textSize = 16f; setPadding(0, 24, 0, 24)
        })
        col.addView(MaterialButton(this).apply { text = "Open Dynamic Island → Modules"; setOnClickListener { HostVerifier.openHostModules(this@MainActivity) } })
        setContentView(col)
    }
}
