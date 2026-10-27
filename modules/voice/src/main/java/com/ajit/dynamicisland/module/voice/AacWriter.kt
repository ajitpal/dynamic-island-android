package com.ajit.dynamicisland.module.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Encodes 16-bit mono PCM to a small AAC .m4a (32 kbps, roughly 14 MB per hour). Not thread-safe. */
class AacWriter(file: File, private val sampleRate: Int = 16_000) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samples = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private fun ptsUs() = samples * 1_000_000L / sampleRate

    fun write(pcm: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(idx) ?: continue
            buf.clear()
            val count = minOf(n - off, buf.remaining() / 2)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, count)
            codec.queueInputBuffer(idx, 0, count * 2, ptsUs(), 0)
            samples += count; off += count
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 300) return }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                }
                o >= 0 -> {
                    val b = codec.getOutputBuffer(o)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (b != null && info.size > 0 && muxing) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Flush and close. Returns false if nothing usable was written. */
    fun finish(): Boolean {
        try {
            var idx = -1; var tries = 0
            while (idx < 0 && tries++ < 100) { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (_: Exception) {}
        runCatching { codec.stop() }; runCatching { codec.release() }
        val ok = muxing && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        return ok
    }
}
