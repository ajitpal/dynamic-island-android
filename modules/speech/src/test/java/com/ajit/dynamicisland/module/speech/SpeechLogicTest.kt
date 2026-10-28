package com.ajit.dynamicisland.module.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeechLogicTest {
    // ---- restricted CTC decoding ----
    private val toks = listOf("<s>", "<pad>", "</s>", "<unk>", " ", "न", "म", "स", "्", "त", "े", "a", "b", "ن", "م", "ਸ", "1")
    private fun frames(vararg ids: Int, alt: Int = -1): FloatArray {
        // One frame per id; [alt] gets an even higher score than the intended token (to test masking).
        val v = toks.size; val l = FloatArray(ids.size * v) { -5f }
        ids.forEachIndexed { t, id -> l[t * v + id] = 5f; if (alt >= 0) l[t * v + alt] = 9f }
        return l
    }

    @Test fun decodesAndCollapsesRepeatsAndBlanks() {
        val c = Ctc(toks)
        assertEquals("नमस्ते", c.decode(frames(5, 5, 0, 6, 7, 7, 8, 9, 10), 9))
        assertEquals("न न", c.decode(frames(5, 4, 5), 3))
    }

    @Test fun neverWritesUrduOrGurmukhi() {
        val c = Ctc(toks)
        assertFalse(c.allowed[13]); assertFalse(c.allowed[14]); assertFalse(c.allowed[15]); assertFalse(c.allowed[0])
        // Urdu "ن" scores highest in every frame, but the Devanagari token is what comes out.
        assertEquals("नमस", c.decode(frames(5, 6, 7, alt = 13), 3))
        assertTrue(c.allowed[11] && c.allowed[16])
    }

    @Test fun readsTokensIncludingTheSpaceToken() {
        val f = java.io.File.createTempFile("tokens", ".txt").apply { writeText("<s> 0\n<pad> 1\n  2\nक 3\n") }
        assertEquals(listOf("<s>", "<pad>", " ", "क"), Ctc.readTokens(f))
    }

    // ---- speech segmentation ----
    private fun probFromEnergy(f: FloatArray): Float { var e = 0.0; f.forEach { e += it * it }; return if (e / f.size > 1e-4) 0.9f else 0.05f }
    private fun tone(sec: Double, amp: Float) = FloatArray((sec * 16000).toInt()) { (amp * sin(2 * PI * 220 * it / 16000)).toFloat() }
    private fun silence(sec: Double) = FloatArray((sec * 16000).toInt())

    @Test fun splitsAtPausesAndKeepsQuietSpeakers() {
        val pieces = mutableListOf<Pair<Double, Double>>()
        val seg = SpeechSegmenter(::probFromEnergy) { s, x -> pieces += s / 16000.0 to x.size / 16000.0 }
        val audio = silence(1.0) + tone(3.0, 0.5f) + silence(1.0) + tone(2.0, 0.02f) + silence(1.0)
        audio.toList().chunked(1600).forEach { seg.accept(it.toFloatArray()) }
        seg.flush()
        assertEquals(2, pieces.size)
        assertEquals(0.8, pieces[0].first, 0.1)     // starts just before the speech (padding)
        assertEquals(4.8, pieces[1].first, 0.1)
    }

    @Test fun capsLongSpeechWithoutPauses() {
        val pieces = mutableListOf<Double>()
        val seg = SpeechSegmenter(::probFromEnergy, maxSec = 20.0) { _, x -> pieces += x.size / 16000.0 }
        seg.accept(tone(45.0, 0.3f)); seg.flush()
        assertTrue(pieces.all { it <= 20.05 }); assertEquals(45.0, pieces.sum(), 0.1)
    }

    @Test fun dropsBlipsShorterThanSpeech() {
        val pieces = mutableListOf<Double>()
        val seg = SpeechSegmenter(::probFromEnergy) { _, x -> pieces += x.size / 16000.0 }
        seg.accept(silence(1.0) + tone(0.1, 0.5f) + silence(2.0)); seg.flush()
        assertTrue(pieces.isEmpty())
    }

    // ---- resampling ----
    @Test fun resamplesAndChunkingDoesNotChangeTheResult() {
        val x = FloatArray(48_000) { sin(2 * PI * 440 * it / 48_000).toFloat() }
        val whole = Resampler(16_000).process(x, 48_000)
        assertEquals(16_000.0, whole.size.toDouble(), 2.0)
        val r = Resampler(16_000)
        val pieces = x.toList().chunked(1234).flatMap { r.process(it.toFloatArray(), 48_000).toList() }.toFloatArray()
        assertArrayEquals(whole, pieces, 1e-6f)
        // Still a 440 Hz sine at the new rate.
        assertTrue((0 until 1000).all { abs(whole[it] - sin(2 * PI * 440 * it / 16_000).toFloat()) < 0.02f })
    }

    @Test fun levelEvensOutAQuietVoice() {
        val x = tone(2.0, 0.5f) + tone(2.0, 0.02f)
        val y = Level.even(x)
        // The quiet voice (0.02) is raised towards the loud one (0.5), up to the 20× cap.
        val quiet = y.copyOfRange(40000, 56000).maxOf { abs(it) }
        assertTrue("quiet part raised: $quiet", quiet > 0.3f)
        // Silence isn't blown up.
        assertEquals(0f, Level.even(FloatArray(16000) + tone(1.0, 0.5f)).copyOfRange(0, 4000).maxOf { abs(it) }, 1e-6f)
    }
}
