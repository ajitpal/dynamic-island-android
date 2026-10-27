package com.ajit.dynamicisland.module.voice

/** One finalised line of transcript. [startMs] is relative to the start of the recording. */
data class Line(val startMs: Long, val text: String)

/** Pure helpers for transcripts (unit-tested, no Android). */
object Notes {
    private val ACTION_CUES = Regex(
        "(?i)\\b(i'll|i will|we'll|we will|you'll|will send|will share|will check|need to|needs to|have to|has to|" +
            "should|must|let's|action item|todo|to-do|to do:|follow up|follow-up|deadline|due|assign(ed)?|owner|" +
            "by (monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|tonight|eod|eow|end of (the )?(day|week|month)|next week))\\b"
    )
    private val FILLER = Regex("(?i)^(uh|um|okay|ok|yeah|yes|no|right|so)[,.!? ]*$")

    /** Sentences that sound like commitments or tasks: a starting point for real notes. */
    fun actionItems(lines: List<Line>, max: Int = 12): List<Line> =
        lines.flatMap { l -> splitSentences(l.text).map { Line(l.startMs, it) } }
            .filter { s -> val words = s.text.split(Regex("\\s+")).size; words in 4..40 && ACTION_CUES.containsMatchIn(s.text) && !FILLER.matches(s.text) }
            .distinctBy { it.text.lowercase() }
            .take(max)

    fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun wordCount(lines: List<Line>) = lines.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }

    /** Plain-text export with timestamps. */
    fun toText(title: String, dateLabel: String, durationMs: Long, lines: List<Line>): String = buildString {
        appendLine(title); appendLine("$dateLabel · ${clock(durationMs)}"); appendLine()
        val items = actionItems(lines)
        if (items.isNotEmpty()) {
            appendLine("Possible action items"); items.forEach { appendLine("• ${it.text}  [${clock(it.startMs)}]") }; appendLine()
        }
        appendLine("Transcript"); lines.forEach { appendLine("[${clock(it.startMs)}] ${it.text}") }
    }
}
