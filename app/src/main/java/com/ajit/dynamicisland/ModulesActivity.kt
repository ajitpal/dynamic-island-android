package com.ajit.dynamicisland

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Kept for modules built with SDK 1 (HostVerifier.openHostModules opens this class by name):
 * it now opens the Modules tab of the main screen.
 */
class ModulesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MODULES)
            .apply { intent.getStringExtra(MainActivity.EXTRA_MODULE)?.let { putExtra(MainActivity.EXTRA_MODULE, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
