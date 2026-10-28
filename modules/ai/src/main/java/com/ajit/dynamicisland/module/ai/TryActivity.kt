package com.ajit.dynamicisland.module.ai

import android.content.Intent
import android.os.Bundle
import android.app.Activity

/** "Try AI" shortcut: opens the main screen scrolled to the Try it box. */
class TryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FOCUS_TRY, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
