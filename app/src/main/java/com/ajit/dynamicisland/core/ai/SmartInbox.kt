package com.ajit.dynamicisland.core.ai

import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.core.inbox.Otp

/**
 * Prompts for the optional Smart Inbox features. Text goes only to the on-device AI Engine module
 * (verified by certificate), and only when the user turned the feature on. Pure Kotlin, unit-tested.
 */
object SmartInbox {
    const val DIGEST_TOKENS = 420
    const val REPLY_TOKENS = 90

    val DIGEST_SYSTEM = "You summarise a person's phone notifications into a short catch-up in English. " +
        "Use only the notifications given. Never invent details. Never repeat one-time passwords or codes. " +
        "Answer with two headings: \"## Needs attention\" (messages that ask something, payments, deadlines, deliveries, missed calls) " +
        "and \"## Everything else\" (one short line per app). Use bullets starting with \"- \". At most 10 bullets in total."

    /**
     * The notifications for the model, newest first and grouped by app: at most [perApp] lines per app
     * plus "and N more", identical lines once, within [maxChars]. Short prompts matter: on a mid-range
     * phone the model reads roughly 20–50 words a second before it writes anything. OTPs are masked.
     */
    fun digestPrompt(entries: List<InboxEntry>, maxChars: Int = 3000, perApp: Int = 4): String {
        val sb = StringBuilder("Notifications (newest first):\n")
        val byApp = LinkedHashMap<String, MutableList<InboxEntry>>()
        entries.forEach { byApp.getOrPut(it.app) { mutableListOf() } += it }
        loop@ for ((app, list) in byApp) {
            val lines = list.map { e -> mask("${e.title}: ${e.text}".replace(Regex("\\s+"), " ").trim()).take(140) }.distinct()
            for (body in lines.take(perApp)) {
                val line = "- [$app] $body\n"
                if (sb.length + line.length > maxChars) break@loop
                sb.append(line)
            }
            if (lines.size > perApp) {
                val more = "- [$app] and ${lines.size - perApp} more\n"
                if (sb.length + more.length > maxChars) break@loop
                sb.append(more)
            }
        }
        sb.append("\nWrite the catch-up now.")
        return sb.toString()
    }

    /**
     * The summary as (heading, bullets) for the Catch Me Up sheet. Text without headings is one "Summary"
     * section. Small models don't always follow the format (seen with Qwen3 0.6B: "- **## Needs attention**",
     * "- - [WhatsApp] …"), so headings are recognised inside bullets and bold, and repeated bullets are removed.
     */
    fun sections(text: String): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        for (raw in text.lines()) {
            var l = raw.trim()
            if (l.isEmpty()) continue
            while (Regex("^[-*•]\\s+").containsMatchIn(l)) l = l.replaceFirst(Regex("^[-*•]\\s+"), "")
            val plain = l.replace("**", "").replace("__", "").trim()
            val heading = when {
                plain.startsWith("#") -> plain.trimStart('#').trim().trimEnd(':')
                (l.startsWith("**") && l.endsWith("**") || plain.endsWith(":")) && plain.length <= 40 && HEADINGS.any { plain.lowercase().startsWith(it) } -> plain.trimEnd(':')
                else -> null
            }
            if (heading != null) { if (heading.isNotBlank()) out += titleCase(heading) to mutableListOf(); continue }
            if (out.isEmpty()) out += "Summary" to mutableListOf()
            if (plain.isNotEmpty()) out.last().second += plain
        }
        return out.filter { it.second.isNotEmpty() }
    }

    private val HEADINGS = listOf("needs attention", "everything else", "summary", "important", "other")

    private fun titleCase(s: String) = s.split(" ").joinToString(" ") { w ->
        if (w.lowercase() in setOf("a", "an", "the", "and", "or", "of", "in", "on", "to", "for")) w.lowercase() else w.replaceFirstChar { it.uppercase() }
    }.replaceFirstChar { it.uppercase() }

    /** Hide anything that looks like a one-time code. */
    fun mask(s: String): String {
        val otp = Otp.extract(s) ?: return s
        return s.replace(otp, "••••")
    }

    /** The digest shortened for the island: headings dropped, bullets kept, at most ~6 lines. */
    fun forIsland(text: String, maxLines: Int = 6): String {
        val out = mutableListOf<String>()
        for ((heading, bullets) in sections(text)) {
            if (heading.equals("Everything Else", true) && out.isNotEmpty()) out += "Also:"
            for (b in bullets) { out += "• $b"; if (out.size >= maxLines) break }
            if (out.size >= maxLines) break
        }
        return out.joinToString("\n").take(400)
    }

    val REPLY_SYSTEM = "You suggest short replies to a chat message, written as the person who received it. " +
        "Reply in the same language and tone as the message. Give exactly 3 different options, one per line, " +
        "each under 12 words. No numbering, no quotes, no explanations."

    fun replyPrompt(from: String, message: String) =
        "Message from ${from.take(60)}:\n\"\"\"\n${mask(message).take(600)}\n\"\"\"\nThree short replies:"

    /** Clean the model's lines into up to 3 reply chips. */
    fun parseReplies(text: String): List<String> = text.lines()
        .map { it.trim().replace(Regex("^(?:[-*•]|\\d+[.)]|option \\d+:?)\\s*", RegexOption.IGNORE_CASE), "").trim().trim('"', '“', '”', '\'').trim() }
        .filter { it.isNotEmpty() && it.length <= 120 && !it.endsWith(":") && !it.startsWith("#") }
        .distinct().take(3)
}
