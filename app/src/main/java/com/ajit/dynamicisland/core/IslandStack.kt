package com.ajit.dynamicisland.core

/**
 * Which activities the island shows when several run at once (2.0: "three at once").
 *
 * Apple's island shows at most two shapes: the island and one small circle. We keep that
 * look and add a "+N" badge on the circle when more are running:
 *  - [primary] is drawn in the island,
 *  - [bubble] is the next one in the cycle, drawn in the circle,
 *  - [hidden] is how many more are running (the badge). Long-press the circle to see all.
 *
 * Tapping the circle makes [bubble] the primary, so repeated taps walk through every
 * running activity in priority order and wrap around. Pure Kotlin so it is unit-tested.
 */
data class IslandStack<T>(val primary: T?, val bubble: T?, val hidden: Int, val all: List<T>) {
    val count get() = all.size

    companion object {
        /** How many rows the long-press list shows. */
        const val LIST_MAX = 4

        /**
         * @param ordered visible activities, highest priority first (without any user choice applied).
         * @param preferredId the activity the user switched to, if it is still running.
         */
        fun <T> arrange(ordered: List<T>, preferredId: String?, id: (T) -> String): IslandStack<T> {
            if (ordered.isEmpty()) return IslandStack(null, null, 0, emptyList())
            val i = ordered.indexOfFirst { id(it) == preferredId }.coerceAtLeast(0)
            val primary = ordered[i]
            val bubble = if (ordered.size > 1) ordered[(i + 1) % ordered.size] else null
            return IslandStack(primary, bubble, (ordered.size - 2).coerceAtLeast(0), ordered)
        }

        /**
         * Keeps the user's choice unless something more urgent just started: a call that was not
         * running before takes the island back (the user can still switch away from it).
         */
        fun <T> keepPreferred(
            preferredId: String?, ordered: List<T>, previousIds: Set<String>,
            id: (T) -> String, urgent: (T) -> Boolean,
        ): String? {
            if (preferredId == null) return null
            if (ordered.none { id(it) == preferredId }) return null
            if (ordered.any { urgent(it) && id(it) !in previousIds && id(it) != preferredId }) return null
            return preferredId
        }

        /** "+1", "+2"… for the circle's badge, or null when nothing is hidden. */
        fun badge(hidden: Int): String? = if (hidden > 0) "+$hidden" else null

        /** TalkBack text for the circle. */
        fun bubbleDescription(title: CharSequence, hidden: Int): String = buildString {
            append("Switch to ").append(title)
            if (hidden > 0) append(". ").append(hidden).append(if (hidden == 1) " more activity" else " more activities")
                .append(". Touch and hold to see all")
        }
    }
}
