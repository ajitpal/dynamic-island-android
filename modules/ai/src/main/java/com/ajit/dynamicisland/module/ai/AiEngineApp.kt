package com.ajit.dynamicisland.module.ai

import android.app.Application
import android.os.Handler
import android.os.Looper

/** Sets up diagnostics, checks whether the last model load crashed, and lets the engine restart itself. */
class AiEngineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Thread { Diag.checkCrashedLoad(this) }.start()
        LlmEngine.restartProcess = {
            // Give the error replies a moment to reach the apps waiting for them, then start clean.
            // A bound service is restarted by Android the next time an app binds to it.
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1500)
        }
    }
}
