package com.ajit.dynamicisland.module.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Once a day, on any network, while the phone is in use: check for updates and say so once per release. */
class CheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!UPrefs.auto(this) || !UPrefs.enabledByIsland(this)) return false
        Handler(Looper.getMainLooper()).post {
            Updater.check(this) { n ->
                if (n > 0) announce(this)
                jobFinished(params, n < 0)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 4711
        private const val CHANNEL = "updates"

        /** (Re)schedule or cancel the daily check to match the settings. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!UPrefs.auto(ctx) || !UPrefs.enabledByIsland(ctx)) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * 3600_000L, 6 * 3600_000L)
                .setPersisted(true)
                .build())
        }

        /** A notification (works even when the island is off) and an island card, once per release. */
        fun announce(ctx: Context) {
            val av = Updater.state as? Updater.State.Available ?: return
            if (UPrefs.announced(ctx) == av.release.version) return
            UPrefs.setAnnounced(ctx, av.release.version)
            UpdaterModule.instance?.announce(av)
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val names = av.items.joinToString(", ") { it.update.app.name }
            nm.notify(1, android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(if (av.items.size == 1) "Update available" else "${av.items.size} updates available")
                .setContentText("$names · Dynamic Island ${av.release.version}")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
