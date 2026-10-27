package com.ajit.dynamicisland.module.voice

import android.content.Context
import com.ajit.dynamicisland.sdk.ai.AiClient
import com.ajit.dynamicisland.sdk.ai.AiContract

/** Writes AI notes for a recording through the AI Engine module. Blocking: run on a worker thread. */
class NoteWriter(private val ctx: Context) {
    class Failed(val code: Int, message: String) : Exception(message)

    /** [progress] gets a short status line, a 0..1 fraction and the text being written. */
    fun write(rec: Recording, t: Template, progress: (String, Float, String) -> Unit, isCancelled: () -> Boolean): AiNotes {
        if (rec.lines.isEmpty()) throw Failed(AiContract.E_FAILED, "There's no transcript to summarise")
        val ai = AiClient(ctx)
        try {
            val status = ai.statusBlocking() ?: throw Failed(AiContract.E_UNAVAILABLE, "Install the AI Engine module to write notes")
            if (!status.getBoolean(AiContract.S_READY)) throw Failed(AiContract.E_NO_MODEL, "Open AI Engine and download a model first")
            val model = status.getString(AiContract.S_MODEL)
            val maxIn = status.getInt(AiContract.S_MAX_INPUT_CHARS, 9000)
            val sys = Summary.system(t)
            val partBudget = (maxIn - sys.length - 900).coerceAtLeast(1500)
            val parts = Summary.chunk(rec.lines, partBudget)
            val single = parts.size == 1
            // Map steps + merge steps (roughly), for the progress bar.
            val totalSteps = parts.size + if (single) 0 else 1 + parts.size / 6
            var step = 0

            fun ask(prompt: String, tokens: Int, label: String): String {
                if (isCancelled()) throw Failed(AiContract.E_CANCELLED, "Cancelled")
                val r = ai.generateBlocking(sys, prompt, tokens, 0.2f, onPartial = { progress(label, step.toFloat() / totalSteps, it) }, isCancelled = isCancelled)
                step++
                return r.text ?: throw Failed(r.errorCode, r.error ?: "The AI Engine couldn't write notes")
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
            val result = AiNotes(t, title, sections, model, System.currentTimeMillis())
            if (result.isEmpty()) throw Failed(AiContract.E_FAILED, "The model didn't return usable notes. Try again, or try a bigger model in AI Engine.")
            return result
        } finally { ai.close() }
    }
}
