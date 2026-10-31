package com.ajit.dynamicisland.module.screen

import android.content.Context

/** Screen Recorder settings. Defaults: no sound, 1080p, 30 fps, 3-second countdown. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("screen", Context.MODE_PRIVATE)

    fun audio(c: Context) = runCatching { Audio.valueOf(sp(c).getString("audio", Audio.NONE.name)!!) }.getOrDefault(Audio.NONE)
    fun setAudio(c: Context, a: Audio) = sp(c).edit().putString("audio", a.name).apply()

    fun quality(c: Context) = runCatching { Quality.valueOf(sp(c).getString("quality", Quality.HD1080.name)!!) }.getOrDefault(Quality.HD1080)
    fun setQuality(c: Context, q: Quality) = sp(c).edit().putString("quality", q.name).apply()

    fun fps(c: Context) = sp(c).getInt("fps", 30).let { if (it == 60) 60 else 30 }
    fun setFps(c: Context, f: Int) = sp(c).edit().putInt("fps", f).apply()

    fun countdown(c: Context) = sp(c).getBoolean("countdown", true)
    fun setCountdown(c: Context, on: Boolean) = sp(c).edit().putBoolean("countdown", on).apply()
}
