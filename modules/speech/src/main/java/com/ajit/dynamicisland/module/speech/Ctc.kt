package com.ajit.dynamicisland.module.speech

import java.io.File

/**
 * Greedy CTC decoding restricted to the scripts people expect: Devanagari for Hindi and Latin for
 * English words (plus digits and a little punctuation). Omnilingual ASR doesn't know which language
 * it hears, and spoken Hindi and Urdu are close, so unrestricted decoding sometimes writes a Hindi
 * sentence in Urdu (or Gujarati) script. Masking every other token fixes that and also lowers the
 * error rate (FLEURS Hindi, 40 clips: 300M model WER 24.7% → 18.3%, CER 25.6% → 9.8%).
 *
 * Pure Kotlin, unit-tested.
 */
class Ctc(val tokens: List<String>, val blank: Int = 0) {
    /** True for tokens we may output. */
    val allowed: BooleanArray = BooleanArray(tokens.size) { i -> i != blank && tokenAllowed(tokens[i]) }

    /** [logits] is frames × vocab, row-major. */
    fun decode(logits: FloatArray, frames: Int, vocab: Int = tokens.size): String {
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until frames) {
            var best = blank; var bestV = logits[t * vocab + blank]
            val base = t * vocab
            for (k in 0 until vocab) {
                if (!allowed[k]) continue
                val v = logits[base + k]
                if (v > bestV) { bestV = v; best = k }
            }
            if (best != prev && best != blank) sb.append(tokens[best])
            prev = best
        }
        return sb.toString().replace('▁', ' ').replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val SPECIAL = setOf("<s>", "<pad>", "</s>", "<unk>")
        private const val PUNCT = ".,?!'-%:"

        fun tokenAllowed(t: String): Boolean {
            if (t in SPECIAL || t.isEmpty()) return false
            return t.all { ch ->
                val cp = ch.code
                ch == ' ' || ch == '▁' || ch in PUNCT || ch.isDigit() && cp < 0x80 ||
                    cp in 0x0900..0x097F || cp in 0xA8E0..0xA8FF || cp == 0x200C || cp == 0x200D ||
                    (cp < 0x80 && ch.isLetter())
            }
        }

        /** tokens.txt: one "<token> <id>" per line; the space token's line starts with a space. */
        fun readTokens(f: File): List<String> {
            val pairs = f.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
                val cut = line.lastIndexOf(' ')
                val tok = line.substring(0, cut).ifEmpty { " " }
                tok to line.substring(cut + 1).trim().toInt()
            }
            val out = Array(pairs.maxOf { it.second } + 1) { "" }
            pairs.forEach { (t, id) -> out[id] = t }
            return out.toList()
        }
    }
}

/**
 * Splits a recording into utterances for the recognizer, using a speech probability for each 32 ms
 * frame (Silero VAD in the app; anything in tests). An utterance ends after [minSilenceSec] without
 * speech; one longer than [maxSec] is cut at its least speech-like frame. Each piece holds one voice
 * and one loudness, which the recognizer needs: two speakers at different levels in one piece made
 * it lose both (desktop test, FLEURS Hindi).
 *
 * Pure Kotlin, unit-tested.
 */
class SpeechSegmenter(
    private val prob: (FloatArray) -> Float,
    private val rate: Int = 16_000,
    private val onSpeech: Float = 0.5f, private val offSpeech: Float = 0.35f,
    private val minSilenceSec: Double = 0.5, private val minSpeechSec: Double = 0.25,
    private val padSec: Double = 0.2, private val maxSec: Double = 20.0,
    private val onPiece: (startSample: Long, samples: FloatArray) -> Unit,
) {
    companion object { const val FRAME = 512 }

    private val pending = FloatArray(FRAME); private var pendingN = 0
    private var frameIndex = 0L                    // frames seen so far
    private val history = ArrayDeque<FloatArray>() // recent frames, for padding before speech starts
    private val padFrames = (padSec * rate / FRAME).toInt().coerceAtLeast(1)
    private val silenceFrames = (minSilenceSec * rate / FRAME).toInt().coerceAtLeast(1)
    private val maxFrames = (maxSec * rate / FRAME).toInt()
    private val minSpeechFrames = (minSpeechSec * rate / FRAME).toInt().coerceAtLeast(1)

    private var inSpeech = false
    private var startFrame = 0L
    private val frames = ArrayList<FloatArray>()
    private val probs = ArrayList<Float>()
    private var silentRun = 0
    private var voiced = 0

    fun accept(samples: FloatArray, n: Int = samples.size) {
        var i = 0
        while (i < n) {
            val take = minOf(FRAME - pendingN, n - i)
            System.arraycopy(samples, i, pending, pendingN, take); pendingN += take; i += take
            if (pendingN == FRAME) { frame(pending.copyOf()); pendingN = 0 }
        }
    }

    fun flush() {
        if (pendingN > 0) { frame(pending.copyOf().also { java.util.Arrays.fill(it, pendingN, FRAME, 0f) }); pendingN = 0 }
        if (inSpeech) end(frames.size)
    }

    private fun frame(f: FloatArray) {
        val p = prob(f)
        if (!inSpeech) {
            if (p >= onSpeech) {
                inSpeech = true; silentRun = 0; voiced = 1
                frames.clear(); probs.clear()
                history.forEach { frames += it; probs += 0f }
                startFrame = frameIndex - history.size
                frames += f; probs += p
            }
            history.addLast(f); if (history.size > padFrames) history.removeFirst()
        } else {
            frames += f; probs += p
            if (p < offSpeech) silentRun++ else { silentRun = 0; voiced++ }
            when {
                silentRun >= silenceFrames -> end(frames.size - silentRun + padFrames)
                frames.size >= maxFrames -> {
                    // Too long without a pause: cut at the least speech-like frame in the last third.
                    val from = frames.size * 2 / 3; var best = frames.size - 1
                    for (k in from until frames.size) if (probs[k] < probs[best]) best = k
                    end(best + 1, keepRest = true)
                }
            }
        }
        frameIndex++
    }

    /** Emit the first [n] frames of the current utterance. */
    private fun end(n: Int, keepRest: Boolean = false) {
        val count = n.coerceIn(1, frames.size)
        if (voiced >= minSpeechFrames) {
            val out = FloatArray(count * FRAME)
            for (k in 0 until count) System.arraycopy(frames[k], 0, out, k * FRAME, FRAME)
            onPiece(startFrame * FRAME, out)
        }
        if (keepRest && count < frames.size) {
            val rest = frames.subList(count, frames.size).toList(); val restP = probs.subList(count, probs.size).toList()
            frames.clear(); probs.clear(); frames += rest; probs += restP
            startFrame += count; silentRun = 0; voiced = minSpeechFrames
        } else {
            inSpeech = false; frames.clear(); probs.clear(); silentRun = 0; voiced = 0
            history.clear()
        }
    }
}
