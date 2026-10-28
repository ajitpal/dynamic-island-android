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

    /** One line per notification, newest first, within [maxChars]. OTP codes are masked before sending. */
    fun digestPrompt(entries: List<InboxEntry>, maxChars: Int = 6000): String {
        val sb = StringBuilder("Notifications (newest first):\n")
        for (e in entries) {
            val body = mask("${e.title}: ${e.text}".replace(Regex("\\s+"), " ").trim()).take(180)
            val line = "- [${e.app}] $body\n"
            if (sb.length + line.length > maxChars) break
            sb.append(line)
        }
        sb.append("\nWrite the catch-up now.")
        return sb.toString()
    }

    /** Hide anything that looks like a one-time code. */
    fun mask(s: String): String {
        val otp = Otp.extract(s) ?: return s
        return s.replace(otp, "••••")
    }

    /** The digest shortened for the island: headings dropped, bullets kept, at most ~6 lines. */
    fun forIsland(text: String, maxLines: Int = 6): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (l in lines) {
            if (l.startsWith("#")) { val h = l.trimStart('#').trim(); if (h.equals("Everything else", true) && out.isNotEmpty()) out += "Also:"; continue }
            out += "• " + l.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
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
