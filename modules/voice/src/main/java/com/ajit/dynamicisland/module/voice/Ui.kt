package com.ajit.dynamicisland.module.voice

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

internal fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Activity.text(parent: LinearLayout, s: CharSequence, sp: Float, bold: Boolean = false, alpha: Float = 1f) =
    TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); this.alpha = alpha; setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(false)
    }.also { parent.addView(it) }

/** Microphone, plus notifications on Android 13+ (the recording notification). */
internal fun neededPermissions(): List<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
    else listOf(android.Manifest.permission.RECORD_AUDIO)
