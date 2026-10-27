package com.ajit.dynamicisland.module.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Receives SHOW / REMOVE from automation apps. Ignores anything without the right secret key. */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ex = intent.extras ?: return
        if (!Store.check(context, ex.getString("key"))) { Log.w("AutomationBridge", "Rejected: wrong or missing key"); return }
        when (intent.action) {
            AutomationModule.ACTION_SHOW -> AutomationModule.show(context, ex)
            AutomationModule.ACTION_REMOVE -> AutomationModule.remove(ex.getString("id"))
        }
    }
}
