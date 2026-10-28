package com.ajit.dynamicisland.module.updater

import android.content.Context

/** The Updater's few settings. */
object UPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** "Check Automatically" (once a day). On by default. */
    fun auto(c: Context) = sp(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) { sp(c).edit().putBoolean("auto", on).apply(); CheckJob.schedule(c) }

    /** False when the person turned the Updater off in the island's Modules tab: no checks at all then. */
    fun enabledByIsland(c: Context) = sp(c).getBoolean("island_on", true)
    fun setEnabledByIsland(c: Context, on: Boolean) { sp(c).edit().putBoolean("island_on", on).apply(); CheckJob.schedule(c) }

    fun lastCheck(c: Context) = sp(c).getLong("last_check", 0)
    fun setLastCheck(c: Context, t: Long) = sp(c).edit().putLong("last_check", t).apply()

    fun availableCount(c: Context) = sp(c).getInt("available", 0)
    fun availableRelease(c: Context): String? = sp(c).getString("available_release", null)
    fun setAvailable(c: Context, n: Int, release: String?) = sp(c).edit().putInt("available", n).putString("available_release", release).apply()

    /** The release we last told the person about, so each release is announced once. */
    fun announced(c: Context): String? = sp(c).getString("announced", null)
    fun setAnnounced(c: Context, release: String) = sp(c).edit().putString("announced", release).apply()
}
