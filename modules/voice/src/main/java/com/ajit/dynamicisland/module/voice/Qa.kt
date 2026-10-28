package com.ajit.dynamicisland.module.voice

/** "Ask about this meeting": pick the parts of the transcript that matter for a question. Pure Kotlin. */
object Qa {
    const val ANSWER_TOKENS = 300

    val SYSTEM = "You answer questions about a recorded conversation, using only the notes and transcript excerpts given. " +
        "If the answer isn't in them, say: I couldn't find that in the recording. " +
        "Mention where it was said with timestamps like [12:30]. Keep answers short (1 to 4 sentences, or a few bullets). " +
        "Speech recognition may contain mistakes."

    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "about", "is", "are", "was", "were",
        "be", "been", "do", "did", "does", "what", "who", "when", "where", "why", "how", "which", "that", "this", "it", "we",
        "they", "i", "you", "he", "she", "said", "say", "tell", "me", "us", "any", "there", "meeting", "call", "anyone", "someone",
    )

    fun keywords(q: String): Set<String> =
        q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.map(::stem).toSet()

    private fun stem(w: String) = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /**
     * The transcript itself if it fits in [maxChars]; otherwise the windows of a few lines that share
     * the most words with the question, kept in time order. Always at least the first window.
     */
    fun excerpts(lines: List<Line>, question: String, maxChars: Int, window: Int = 6): String {
        val all = lines.joinToString("\n") { "[${Notes.clock(it.startMs)}] ${it.text.trim()}" }
        if (all.length <= maxChars) return all
        val keys = keywords(question)
        val windows = lines.chunked(window).mapIndexed { i, w ->
            val text = w.joinToString("\n") { "[${Notes.clock(it.startMs)}] ${it.text.trim()}" }
            val words = text.lowercase().split(Regex("[^a-z0-9]+")).map(::stem)
            Triple(i, text, words.count { it in keys })
        }
        val picked = mutableListOf<Triple<Int, String, Int>>()
        var size = 0
        for (w in windows.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })) {
            if (picked.isNotEmpty() && (w.third == 0 && picked.size >= 2)) break
            if (size + w.second.length + 6 > maxChars) continue
            picked += w; size += w.second.length + 6
        }
        return picked.sortedBy { it.first }.joinToString("\n…\n") { it.second }
    }

    fun prompt(question: String, notes: AiNotes?, excerpts: String): String = buildString {
        if (notes != null && !notes.isEmpty()) { appendLine("Notes:"); appendLine(Summary.format(notes.sections.filter { it.second.isNotEmpty() })); appendLine() }
        appendLine("Transcript excerpts:\n\"\"\"\n$excerpts\n\"\"\"")
        appendLine()
        append("Question: ${question.trim().take(300)}")
    }

    private val STAMP = Regex("\\[(\\d{1,2}):(\\d{2})(?::(\\d{2}))?]")

    /** Timestamps like [12:30] or [1:02:03] in an answer, as (start, end, ms). */
    fun stamps(answer: String): List<Triple<Int, Int, Long>> = STAMP.findAll(answer).map { m ->
        val (a, b, c) = m.destructured
        val ms = if (c.isNotEmpty()) (a.toLong() * 3600 + b.toLong() * 60 + c.toLong()) * 1000 else (a.toLong() * 60 + b.toLong()) * 1000
        Triple(m.range.first, m.range.last + 1, ms)
    }.toList()
}

data class QaItem(val question: String, val answer: String, val at: Long, val by: String?)
