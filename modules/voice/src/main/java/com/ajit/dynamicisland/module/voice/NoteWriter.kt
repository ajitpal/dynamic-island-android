package com.ajit.dynamicisland.module.voice

import android.content.Context
import com.ajit.dynamicisland.sdk.ai.AiContract

/** Writes AI notes for a recording through the AI Engine module. Blocking: run on a worker thread. */
class NoteWriter(private val ctx: Context) {
    class Failed(val code: Int, message: String) : Exception(message)

    /** [progress] gets a short status line, a 0..1 fraction and the text being written. */
    fun write(rec: Recording, t: Template, progress: (String, Float, String) -> Unit, isCancelled: () -> Boolean): AiNotes {
        if (rec.lines.isEmpty()) throw Failed(AiContract.E_FAILED, "There's no transcript to summarise")
        val ai = Brain(ctx)
        try {
            val (maxIn, _) = ai.capacity() ?: throw Failed(AiContract.E_NO_MODEL,
                if (com.ajit.dynamicisland.sdk.ai.AiClient.isTrusted(ctx)) "Open AI Engine and download a model first" else "Install the AI Engine module to write notes")
            val sys = Summary.system(t)
            val partBudget = (maxIn - sys.length - 900).coerceAtLeast(1500)
            val parts = Summary.chunk(rec.lines, partBudget)
            val single = parts.size == 1
            // Map steps + merge steps (roughly), for the progress bar.
            val totalSteps = parts.size + if (single) 0 else 1 + parts.size / 6
            var step = 0

            fun ask(prompt: String, tokens: Int, label: String): String {
                if (isCancelled()) throw Failed(AiContract.E_CANCELLED, "Cancelled")
                val r = ai.generate(sys, prompt, tokens, 0.2f, onPartial = { progress(label, step.toFloat() / totalSteps, it) }, isCancelled = isCancelled)
                step++
                return r.text ?: throw Failed(r.code, r.error ?: "The AI couldn't write notes")
            }

            var notes = parts.mapIndexed { i, p ->
                ask(Summary.partPrompt(t, p, i, parts.size, single), if (single) Summary.FINAL_ANSWER_TOKENS else Summary.PART_ANSWER_TOKENS,
                    if (single) "Writing notes" else "Reading part ${i + 1} of ${parts.size}")
            }
            // Merge level by level until one set of notes is left.
            while (notes.size > 1) {
                val formatted = notes.map { Summary.format(Summary.parse(t, it).second) }
                var groups = Summary.groupForMerge(formatted, partBudget)
                if (groups.size >= notes.size) groups = formatted.chunked(2) // always make progress
                val last = groups.size == 1
                notes = groups.map { g -> if (g.size == 1 && !last) g[0] else ask(Summary.mergePrompt(t, g, withTitle = last), Summary.FINAL_ANSWER_TOKENS, "Putting the notes together") }
            }
            val (title, sections) = Summary.parse(t, notes.single())
            val result = AiNotes(t, title, sections, ai.usedBy.joinToString(" + ").ifEmpty { null }, System.currentTimeMillis())
            if (result.isEmpty()) throw Failed(AiContract.E_FAILED, "The model didn't return usable notes. Try again, or try a bigger model in AI Engine.")
            return result
        } finally { ai.close() }
    }
}
