package com.ajit.dynamicisland.module.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Omnilingual ASR (Meta, Apache 2.0) CTC model on ONNX Runtime. Input: 16 kHz mono audio, at most
 * about 20 s per call (see [Segmenter]). Same code runs on Android and in the desktop test.
 */
class Recognizer(modelFile: File, tokensFile: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads.coerceIn(1, 8))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    val ctc = Ctc(Ctc.readTokens(tokensFile))

    /**
     * Text for one piece of audio, checked: a piece of several seconds that comes back empty or far
     * too short (a quiet voice next to a loud one can do that) is tried again with its loudness evened
     * out, then as two halves split at the quietest point. Clean speech takes the first, plain pass.
     */
    fun recognize(samples: FloatArray, depth: Int = 0): String {
        val plain = run(samples, even = false)
        if (plausible(plain, samples.size) || samples.size < RATE * 3) return plain
        val evened = run(samples, even = true)
        if (plausible(evened, samples.size)) return evened
        if (depth >= 2) return listOf(plain, evened).maxBy { it.length }
        val cut = Level.quietestCut(samples)
        val halves = listOf(samples.copyOfRange(0, cut), samples.copyOfRange(cut, samples.size))
        val split = halves.joinToString(" ") { recognize(it, depth + 1) }.trim()
        return listOf(plain, evened, split).maxBy { it.length }
    }

    /** Hindi speech is roughly 8–15 characters a second; under ~2.5 means something went wrong. */
    private fun plausible(text: String, samples: Int) = text.length >= samples.toDouble() / RATE * 2.5

    private fun run(samples: FloatArray, even: Boolean): String {
        if (samples.isEmpty()) return ""
        val agc = if (even) Level.even(samples) else samples
        // Zero mean, unit variance (what the model was trained on).
        var mean = 0.0; agc.forEach { mean += it }; mean /= agc.size
        var v = 0.0; agc.forEach { v += (it - mean) * (it - mean) }; v /= agc.size
        val sd = kotlin.math.sqrt(v + 1e-5)
        val x = FloatArray(agc.size) { ((agc[it] - mean) / sd).toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape   // [1, frames, vocab]
                val buf = t.floatBuffer
                val arr = FloatArray(buf.remaining()); buf.get(arr)
                return ctc.decode(arr, shape[1].toInt(), shape[2].toInt())
            }
        }
    }

    override fun close() { session.close() }

    companion object { const val RATE = 16_000 }
}

/**
 * Evens out loudness inside one piece (a near and a far voice, or a quiet speaker after a loud one):
 * each 0.4 s window is scaled towards the same level, with a smooth gain and a floor so silence isn't
 * blown up into noise. Without it, a quiet voice next to a loud one could make the model return
 * nothing for the whole piece (desktop test). Pure Kotlin.
 */
object Level {
    /** Sample index of the quietest 0.1 s in the middle half (where to split a piece in two). */
    fun quietestCut(x: FloatArray, rate: Int = 16_000): Int {
        val win = rate / 10
        var best = x.size / 2; var bestE = Double.MAX_VALUE
        var i = x.size / 4
        while (i + win <= x.size * 3 / 4) {
            var e = 0.0; for (k in i until i + win) e += x[k] * x[k]
            if (e < bestE) { bestE = e; best = i + win / 2 }
            i += win / 2
        }
        return best
    }

    fun even(x: FloatArray, rate: Int = 16_000): FloatArray {
        val win = rate * 4 / 10
        val n = (x.size + win - 1) / win
        if (n <= 1) return x
        val rms = FloatArray(n) { w ->
            var s = 0.0; val a = w * win; val b = minOf(a + win, x.size)
            for (i in a until b) s += x[i] * x[i]
            kotlin.math.sqrt(s / (b - a)).toFloat()
        }
        val loud = rms.sortedDescending()[n / 5]                  // a typical speech level in this piece
        val floor = maxOf(loud * 0.05f, 1e-4f)                     // don't raise silence/noise above this
        val gain = FloatArray(n) { w -> (loud / maxOf(rms[w], floor)).coerceIn(0.25f, 20f) }
        // Smooth the gain across windows and interpolate per sample.
        val g = FloatArray(n) { w -> (gain[maxOf(w - 1, 0)] + 2 * gain[w] + gain[minOf(w + 1, n - 1)]) / 4f }
        return FloatArray(x.size) { i ->
            val pos = i.toFloat() / win - 0.5f
            val a = pos.toInt().coerceIn(0, n - 1); val b = (a + 1).coerceAtMost(n - 1); val t = (pos - a).coerceIn(0f, 1f)
            x[i] * (g[a] * (1 - t) + g[b] * t)
        }
    }
}
