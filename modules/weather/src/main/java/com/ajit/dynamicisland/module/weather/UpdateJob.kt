package com.ajit.dynamicisland.module.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ajit.dynamicisland.sdk.IslandContract

/** Every 30 minutes, on any network: refresh for rain and air alerts. Off when the module is off in the island. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!WPrefs.enabledByIsland(this) || !WPrefs.hasPlace(this)) return false
        Weather.refresh(this) { ok -> jobFinished(params, !ok) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 5211
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!WPrefs.enabledByIsland(ctx) || !WPrefs.hasPlace(ctx)) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L, 10 * 60_000L)
                .setPersisted(true)
                .build())
        }
    }
}

/** Only the genuine island can send this (HOST signature permission). Off stops every download. */
class ModuleStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != IslandContract.ACTION_MODULE_STATE) return
        WPrefs.setEnabledByIsland(ctx, intent.getBooleanExtra(IslandContract.EXTRA_ENABLED, true))
    }
}
