package com.ajit.dynamicisland.module.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.ajit.dynamicisland.sdk.IslandContract

/** Android's answer to an install: ask the person, or done, or failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra(Updater.EXTRA_PKG) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        Updater.onInstallResult(ctx, pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}

/**
 * The island tells a module when the person turns it on or off in the Modules tab. Only the genuine
 * island can send this (the receiver requires its HOST signature permission). Off stops all checks.
 */
class ModuleStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != IslandContract.ACTION_MODULE_STATE) return
        UPrefs.setEnabledByIsland(ctx, intent.getBooleanExtra(IslandContract.EXTRA_ENABLED, true))
    }
}
