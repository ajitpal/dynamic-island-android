package com.ajit.dynamicisland.sdk

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import com.ajit.dynamicisland.sdk.IslandContract as C

/**
 * A module's tile on the Dynamic Island app's Island tab (API 2): one line of status and up to two
 * buttons, e.g. Meeting Notes: "Weekly sync · notes ready" [Record] [Recordings].
 *
 * ```
 * island.postTile(IslandTile.Builder(this).title("Weekly sync").text("Notes ready · 3 action items")
 *     .icon(R.drawable.ic_notes).tapActivity(TranscriptActivity::class.java, id)
 *     .activityAction("record", "Record", RecordActivity::class.java)
 *     .activityAction("list", "Recordings", MainActivity::class.java).build())
 * ```
 * Activities you name must be exported (protect them with IslandContract.PERMISSION_HOST).
 */
class IslandTile private constructor(val bundle: Bundle) {
    class Builder(private val ctx: Context) {
        private val b = Bundle()
        private val actions = ArrayList<Bundle>()
        fun title(t: CharSequence) = apply { b.putCharSequence(C.T_TITLE, t) }
        fun text(t: CharSequence?) = apply { b.putCharSequence(C.T_TEXT, t) }
        fun icon(res: Int) = apply { b.putParcelable(C.T_ICON, Icon.createWithResource(ctx, res)) }
        fun tapActivity(activity: Class<*>, arg: String? = null) = apply {
            b.putString(C.T_TAP_ACTIVITY, activity.name); arg?.let { b.putString(C.T_TAP_ARG, it) }
        }
        /** A button that opens one of your activities. */
        fun activityAction(id: String, title: String, activity: Class<*>, arg: String? = null) = apply {
            actions += Bundle().apply { putString(C.A_ID, id); putString(C.A_TITLE, title); putString(C.A_ACTIVITY, activity.name); arg?.let { putString(C.A_ARG, it) } }
        }
        /** A button that calls your onAction(IslandContract.TILE_ID, id, null). */
        fun action(id: String, title: String) = apply {
            actions += Bundle().apply { putString(C.A_ID, id); putString(C.A_TITLE, title) }
        }
        fun build(): IslandTile {
            require(b.getCharSequence(C.T_TITLE) != null) { "IslandTile needs a title" }
            if (actions.isNotEmpty()) b.putParcelableArrayList(C.T_ACTIONS, ArrayList(actions.take(2)))
            return IslandTile(b)
        }
    }
}
