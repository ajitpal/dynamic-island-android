package com.ajit.dynamicisland.module.voice

/** What kind of notes to write. Each template is a fixed set of headings the model must use. */
enum class Template(val label: String, val sections: List<String>, val hint: String) {
    MEETING("Meeting", listOf("Summary", "Decisions", "Action items", "Open questions"), "a work meeting"),
    STANDUP("Stand-up", listOf("Done", "Next", "Blockers"), "a team stand-up: what people finished, what they will do next, what blocks them"),
    ONE_ON_ONE("1:1", listOf("Topics", "Feedback", "Action items"), "a one-to-one conversation between a manager and a team member"),
    CALL("Call / interview", listOf("Key points", "Questions asked", "Follow-ups"), "a phone call or interview"),
    LECTURE("Lecture / class", listOf("Key ideas", "Terms to remember", "Questions"), "a lecture, class or talk"),
}

/** AI notes for one recording. */
data class AiNotes(
    val template: Template, val title: String?, val sections: List<Pair<String, List<String>>>,
    val model: String?, val createdAt: Long,
) {
    fun isEmpty() = sections.all { it.second.isEmpty() }
}

/**
 * Turns a transcript into notes with a small on-device model: split the transcript into parts that
 * fit the model, write notes for each part ("map"), then merge them ("reduce"). Pure Kotlin, unit-tested.
 */
object Summary {
    const val PART_ANSWER_TOKENS = 380
    const val FINAL_ANSWER_TOKENS = 600

    fun system(t: Template) =
        "You turn transcripts of ${t.hint} into short, accurate notes in English. " +
            "Use only what is said in the transcript. Never invent names, numbers, dates or decisions. " +
            "Speech recognition may contain mistakes: if a word looks wrong, leave it out rather than guess. " +
            "Answer only with the headings asked for, each followed by short bullet points starting with \"- \"."

    private fun headings(t: Template, withTitle: Boolean) = buildString {
        if (withTitle) appendLine("## Title\n- one line, at most 8 words")
        t.sections.forEach { appendLine("## $it") }
    }.trimEnd()

    /** Transcript lines as "[m:ss] text", grouped into parts of at most [maxChars]. */
    fun chunk(lines: List<Line>, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (l in lines) {
            val row = "[${Notes.clock(l.startMs)}] ${l.text.trim()}\n"
            if (sb.isNotEmpty() && sb.length + row.length > maxChars) { parts += sb.toString().trimEnd(); sb.clear() }
            if (row.length > maxChars) { // a single very long line: hard split
                row.chunked(maxChars).forEach { parts += it.trimEnd() }
            } else sb.append(row)
        }
        if (sb.isNotEmpty()) parts += sb.toString().trimEnd()
        return parts
    }

    fun partPrompt(t: Template, part: String, index: Int, total: Int, single: Boolean): String = buildString {
        if (total > 1) appendLine("This is part ${index + 1} of $total of the transcript.")
        appendLine("Transcript:\n\"\"\"\n$part\n\"\"\"")
        appendLine()
        appendLine("Write notes with exactly these headings:")
        appendLine(headings(t, withTitle = single))
        append("Under each heading write 1 to 5 bullets. If there is nothing for a heading, write \"- None\".")
        if (t.sections.contains("Action items")) append(" For action items write who will do what, and by when if it was said.")
    }

    fun mergePrompt(t: Template, partNotes: List<String>, withTitle: Boolean): String = buildString {
        appendLine("Below are notes from consecutive parts of the same ${t.label.lowercase()}. Merge them into one set of notes.")
        appendLine("Remove repeats, keep the most important points, keep it short.")
        partNotes.forEachIndexed { i, n -> appendLine("\nPart ${i + 1}:\n$n") }
        appendLine("\nWrite the merged notes with exactly these headings:")
        appendLine(headings(t, withTitle))
        append("Under each heading write 1 to 6 bullets, or \"- None\".")
    }

    /** Group part notes so each merge request fits, merging level by level if needed. */
    fun groupForMerge(notes: List<String>, maxChars: Int): List<List<String>> {
        val groups = mutableListOf<MutableList<String>>(mutableListOf())
        var size = 0
        for (n in notes) {
            if (groups.last().isNotEmpty() && size + n.length > maxChars) { groups += mutableListOf<String>(); size = 0 }
            groups.last() += n; size += n.length + 12
        }
        return groups
    }

    private val HEADING = Regex("^\\s*(?:#{1,4}\\s*|\\*\\*)?([A-Za-z0-9 /:'’-]{2,40}?)(?:\\*\\*)?\\s*:?\\s*$")
    private val BULLET = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s+(.*)$")
    private val NONE = Regex("(?i)^(none|n/?a|nothing|no (decisions|action items|questions|blockers)[^a-z]*|-)\\.?$")

    /** Parse the model's answer into (heading, bullets). Unknown headings are kept; "None" is dropped. */
    fun parse(t: Template, text: String): Pair<String?, List<Pair<String, List<String>>>> {
        val known = (t.sections + "Title").associateBy { it.lowercase() }
        val out = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val b = BULLET.find(line)
            val h = if (b == null) HEADING.find(line)?.groupValues?.get(1)?.trim()?.trimEnd(':') else null
            when {
                h != null && (known.containsKey(h.lowercase()) || line.trimStart().startsWith("#")) -> {
                    current = known[h.lowercase()] ?: h; out.getOrPut(current) { mutableListOf() }
                }
                b != null -> {
                    val item = b.groupValues[1].trim().removeSurrounding("**").trim()
                    if (item.isNotEmpty() && !NONE.matches(item)) out.getOrPut(current ?: t.sections.first()) { mutableListOf() } += item
                }
                current != null && line.isNotBlank() && !NONE.matches(line.trim()) -> out.getOrPut(current) { mutableListOf() } += line.trim()
            }
        }
        val title = out.remove("Title")?.firstOrNull()?.trim('"', ' ', '.')?.take(80)
        // Keep the template's order first, then anything extra the model added.
        val ordered = t.sections.map { it to (out.remove(it)?.distinct() ?: emptyList()) } + out.map { it.key to it.value.distinct() }
        return title to ordered
    }

    /** Notes back to text (used as input for merging, and for export). */
    fun format(sections: List<Pair<String, List<String>>>): String = sections.joinToString("\n") { (h, items) ->
        "## $h\n" + (if (items.isEmpty()) "- None" else items.joinToString("\n") { "- $it" })
    }
}
