package com.ajit.dynamicisland.module.voice

import android.content.Context

object VoicePrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun autoNotes(ctx: Context) = p(ctx).getBoolean("autoNotes", true)
    fun setAutoNotes(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("autoNotes", on).apply()
    fun template(ctx: Context) = runCatching { Template.valueOf(p(ctx).getString("template", null)!!) }.getOrDefault(Template.MEETING)
    fun setTemplate(ctx: Context, t: Template) = p(ctx).edit().putString("template", t.name).apply()
}
