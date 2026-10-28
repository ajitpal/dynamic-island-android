package com.ajit.dynamicisland.module.voice

import android.content.Context

object VoicePrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun autoNotes(ctx: Context) = p(ctx).getBoolean("autoNotes", true)
    fun setAutoNotes(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("autoNotes", on).apply()
    /** Use Gemini Nano (built into some phones) while Meeting Notes is on screen. Off by default. */
    fun useNano(ctx: Context) = p(ctx).getBoolean("useNano", false)
    fun setUseNano(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("useNano", on).apply()
    /** What people speak in meetings. */
    fun language(ctx: Context) = runCatching { Lang.valueOf(p(ctx).getString("lang", null)!!) }.getOrDefault(Lang.EN)
    fun setLanguage(ctx: Context, l: Lang) = p(ctx).edit().putString("lang", l.name).apply()
    /** Use the Speech Pack for Hindi even when Android's recognizer has Hindi (more accurate on many phones, not live). */
    fun preferSpeechPack(ctx: Context) = p(ctx).getBoolean("preferSpeechPack", false)
    fun setPreferSpeechPack(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("preferSpeechPack", on).apply()
    /** Last known answer to "does Android have offline Hindi here?" (checked again each time the app opens). */
    fun androidHindi(ctx: Context) = runCatching { AndroidHindi.valueOf(p(ctx).getString("androidHindi", null)!!) }.getOrDefault(AndroidHindi.UNKNOWN)
    fun setAndroidHindi(ctx: Context, a: AndroidHindi) = p(ctx).edit().putString("androidHindi", a.name).apply()
    fun template(ctx: Context) = runCatching { Template.valueOf(p(ctx).getString("template", null)!!) }.getOrDefault(Template.MEETING)
    fun setTemplate(ctx: Context, t: Template) = p(ctx).edit().putString("template", t.name).apply()
}
