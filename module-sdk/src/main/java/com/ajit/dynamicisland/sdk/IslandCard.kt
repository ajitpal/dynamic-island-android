package com.ajit.dynamicisland.sdk

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import com.ajit.dynamicisland.sdk.IslandContract as C

/** A tool a module adds to the island's tools panel. [activity]: open this exported activity of your module instead of calling onToolTapped (e.g. to start the mic). */
data class ModuleTool(val id: String, val label: String, val iconRes: Int, val activity: Class<*>? = null)

enum class ActionStyle(val code: Int) { NORMAL(0), POSITIVE(1), NEGATIVE(2) }

/**
 * What a module shows in the island. The island draws it in the user's theme (modules never
 * inject views), so every module looks native and can't break or spoof the island.
 *
 * ```
 * island.post(IslandCard.Builder(this, "standup")
 *     .title("Standup").text("Starts 10:30 · Google Meet")
 *     .icon(R.drawable.ic_event).accent(0xFF0A84FF.toInt())
 *     .countdownTo(startMillis)
 *     .action("join", "Join", ActionStyle.POSITIVE, url = meetLink)
 *     .peek().build())
 * ```
 */
class IslandCard private constructor(val bundle: Bundle) {
    val id: String get() = bundle.getString(C.K_ID).orEmpty()

    class Builder(private val ctx: Context, id: String) {
        private val b = Bundle().apply { putString(C.K_ID, id); putString(C.K_STYLE, C.STYLE_LIVE) }
        private val actions = ArrayList<Bundle>()

        fun style(style: String) = apply { b.putString(C.K_STYLE, style) }
        fun title(t: CharSequence) = apply { b.putCharSequence(C.K_TITLE, t) }
        fun text(t: CharSequence?) = apply { b.putCharSequence(C.K_TEXT, t) }
        fun compact(t: CharSequence?) = apply { b.putCharSequence(C.K_COMPACT, t) }
        fun icon(res: Int, tint: Boolean = true) = apply {
            b.putParcelable(C.K_ICON, Icon.createWithResource(ctx, res)); b.putBoolean(C.K_TINT_ICON, tint)
        }
        fun icon(icon: Icon, tint: Boolean = false) = apply { b.putParcelable(C.K_ICON, icon); b.putBoolean(C.K_TINT_ICON, tint) }
        fun accent(argb: Int) = apply { b.putInt(C.K_ACCENT, argb) }
        fun progress(fraction: Float) = apply { b.putFloat(C.K_PROGRESS, fraction.coerceIn(0f, 1f)) }
        fun countdownTo(wallClockMs: Long) = apply { b.putLong(C.K_COUNTDOWN_TO, wallClockMs); b.putString(C.K_STYLE, C.STYLE_TIMER) }
        fun countUpFrom(wallClockMs: Long) = apply { b.putLong(C.K_COUNT_UP_FROM, wallClockMs); b.putString(C.K_STYLE, C.STYLE_TIMER) }
        fun peek(ms: Long = 5000) = apply { b.putBoolean(C.K_PEEK, true); b.putLong(C.K_PEEK_MS, ms) }
        fun expiresIn(ms: Long) = apply { b.putLong(C.K_EXPIRES_AT, System.currentTimeMillis() + ms) }
        fun version(v: Int) = apply { b.putInt(C.K_VERSION, v) }
        fun tapUrl(url: String) = apply { b.putString(C.K_TAP_URL, url) }
        fun tapCallback() = apply { b.putBoolean(C.K_TAP_CALLBACK, true) }
        /** Open one of your module's exported activities on tap (arg arrives as IslandContract.EXTRA_ARG). */
        fun tapActivity(activity: Class<*>, arg: String? = null) = apply {
            b.putString(C.K_TAP_ACTIVITY, activity.name); arg?.let { b.putString(C.K_TAP_ARG, it) }
        }

        fun action(id: String, title: String, style: ActionStyle = ActionStyle.NORMAL, url: String? = null) = apply {
            actions += Bundle().apply {
                putString(C.A_ID, id); putString(C.A_TITLE, title); putInt(C.A_STYLE, style.code)
                url?.let { putString(C.A_URL, it) }
            }
        }

        /** A button that opens one of your module's exported activities. */
        fun activityAction(id: String, title: String, activity: Class<*>, arg: String? = null, style: ActionStyle = ActionStyle.NORMAL) = apply {
            actions += Bundle().apply {
                putString(C.A_ID, id); putString(C.A_TITLE, title); putInt(C.A_STYLE, style.code)
                putString(C.A_ACTIVITY, activity.name); arg?.let { putString(C.A_ARG, it) }
            }
        }

        /** A button that opens a text field in the island (quick reply style). */
        fun inputAction(id: String, title: String, hint: String) = apply {
            actions += Bundle().apply {
                putString(C.A_ID, id); putString(C.A_TITLE, title); putBoolean(C.A_INPUT, true); putString(C.A_HINT, hint)
            }
        }

        fun build(): IslandCard {
            require(b.getCharSequence(C.K_TITLE) != null) { "IslandCard needs a title" }
            if (actions.isNotEmpty()) b.putParcelableArrayList(C.K_ACTIONS, ArrayList(actions.take(3)))
            return IslandCard(b)
        }
    }
}
