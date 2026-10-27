package com.ajit.dynamicisland.core

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Starting activities from the overlay / background in a way Android 14+ allows. */
object Launcher {
    fun options(): android.os.Bundle = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }.toBundle()

    fun send(ctx: Context, pi: PendingIntent): Boolean = try {
        pi.send(ctx, 0, null, null, null, null, options()); true
    } catch (e: Exception) { false }

    fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, intent.toUri(0).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!send(ctx, pi)) try { ctx.startActivity(intent) } catch (_: Exception) {}
    }

    /** Open a web link or content URI (meeting link, calendar event…). Only safe schemes. */
    fun openUrl(ctx: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("https", "http", "content", "tel", "geo", "mailto")) return
        start(ctx, Intent(Intent.ACTION_VIEW, uri))
    }
}
