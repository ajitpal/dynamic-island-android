package com.ajit.dynamicisland.module.speech

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileDescriptor
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (m4a/AAC, mp3, wav, ogg/opus, flac, …) to 16 kHz mono float
 * samples, in chunks, with the duration known up front for progress.
 */
class AudioDecoder(fd: FileDescriptor) : AutoCloseable {
    private val ex = MediaExtractor().apply { setDataSource(fd) }
    private val track: Int = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ?: throw IllegalArgumentException("No audio in this file")
    private val format = ex.getTrackFormat(track)
    val durationUs: Long = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1

    /** Calls [onChunk] with 16 kHz mono samples until the end, or until it returns false. */
    fun decode(onChunk: (FloatArray, positionUs: Long) -> Boolean) {
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val resampler = Resampler(16_000)
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        val mono = FloatArray(frames)
                        if (floatPcm) { val fb = buf.asFloatBuffer(); for (k in 0 until frames) { var s = 0f; for (c in 0 until channels) s += fb.get(); mono[k] = s / channels } }
                        else { val sb = buf.asShortBuffer(); for (k in 0 until frames) { var s = 0f; for (c in 0 until channels) s += sb.get() / 32768f; mono[k] = s / channels } }
                        codec.releaseOutputBuffer(o, false)
                        val out = resampler.process(mono, rate)
                        if (out.isNotEmpty() && !onChunk(out, info.presentationTimeUs)) return
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally { runCatching { codec.stop() }; codec.release() }
    }

    override fun close() { ex.release() }
}

/**
 * Linear-interpolation resampler that carries its position across chunks, so feeding audio in pieces
 * gives exactly the same result as feeding it at once. Pure Kotlin, unit-tested.
 */
class Resampler(private val outRate: Int) {
    private var pos = 1.0       // position in y = [previous chunk's last sample] + x
    private var prev = 0f

    fun process(x: FloatArray, inRate: Int): FloatArray {
        if (inRate == outRate || x.isEmpty()) return x
        val step = inRate.toDouble() / outRate
        val n = x.size
        val out = ArrayList<Float>((n / step).toInt() + 2)
        while (pos <= n) {
            val i = pos.toInt(); val t = (pos - i).toFloat()
            val yi = if (i == 0) prev else x[i - 1]
            out += if (i == n) yi else yi + ((x[i]) - yi) * t
            pos += step
        }
        pos -= n                // the next chunk's y[0] is this chunk's last sample (y[n])
        prev = x[n - 1]
        return out.toFloatArray()
    }
}
