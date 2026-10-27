package com.ajit.dynamicisland.module.ai

/** Pure text helpers (unit-tested). */
object Text {
    private val THINK = Regex("(?s)<think>.*?(</think>|$)")

    /** Remove a model's hidden reasoning ("<think>…</think>") and tidy whitespace. */
    fun clean(s: String): String = THINK.replace(s, "").replace(Regex("^\\s+"), "").trimEnd()

    /**
     * Streaming chunks may be deltas or the whole text so far, depending on the runtime version.
     * Accept both: if the chunk repeats what we have, it's cumulative.
     */
    fun merge(soFar: String, chunk: String): String =
        if (soFar.isNotEmpty() && chunk.startsWith(soFar)) chunk else soFar + chunk

    /** Rough token estimate for English (≈ 3.5 characters per token), deliberately pessimistic. */
    fun estimateTokens(s: String) = (s.length / 3.2).toInt() + 1

    /** How many characters of input fit, leaving room for the answer. */
    fun maxInputChars(contextTokens: Int, answerTokens: Int) = ((contextTokens - answerTokens - 96).coerceAtLeast(256) * 3.2).toInt()
}
