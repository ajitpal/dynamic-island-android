package com.ajit.dynamicisland.module.screen

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the screen (and optionally sound) into an MP4: a virtual display feeds a hardware H.264
 * encoder, AudioRecord feeds an AAC encoder, and one MediaMuxer writes both. Pausing drops samples
 * and closes the gap in the timestamps, so the file plays straight through.
 *
 * Call [start] once, then [pause]/[resume], then [stop] (blocks until the file is finished).
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int, private val height: Int, private val dpi: Int,
    private val fps: Int, private val audio: Audio,
    private val out: FileDescriptor,
    /** Called once on a worker thread if recording breaks (the service then stops and saves what it has). */
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC

        /**
         * The largest size at most [quality] that this phone's H.264 encoder accepts at [fps], trying
         * 1080p and 720p before giving up. Full-resolution panels can be bigger than some encoders allow.
         */
        fun supportedSize(screenW: Int, screenH: Int, quality: Quality, fps: Int): Pair<Int, Int> {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(AVC, true) } }
                .mapNotNull { runCatching { it.getCapabilitiesForType(AVC).videoCapabilities }.getOrNull() }
            val limits = listOf(quality.maxShortSide, 1080, 720, 480).filter { it <= quality.maxShortSide }.distinct()
            for (limit in limits) {
                val (w, h) = Plan.videoSize(screenW, screenH, limit)
                if (caps.isEmpty() || caps.any { runCatching { it.areSizeAndRateSupported(w, h, fps.toDouble()) || it.isSizeSupported(w, h) }.getOrDefault(false) }) return w to h
            }
            return Plan.videoSize(screenW, screenH, 720)
        }
    }

    private val clock = PauseClock()
    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var expectAudio = false
    private var muxing = false
    private var needKeyFrame = true
    private val lastPts = LongArray(2) { -1 }
    private var wroteVideo = false

    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private val records = mutableListOf<AudioRecord>()
    @Volatile private var stopping = false
    @Volatile private var failed = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    val paused get() = clock.paused
    fun elapsedMs() = clock.elapsedUs(System.nanoTime() / 1000) / 1000

    /** True if sound was asked for but couldn't be opened (the recording continues without it). */
    var audioUnavailable = false
        private set

    fun start() {
        muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (audio != Audio.NONE) expectAudio = openAudio()
        startVideo()
        if (expectAudio) startAudio()
    }

    fun pause() = clock.pause(System.nanoTime() / 1000)

    fun resume() {
        clock.resume(System.nanoTime() / 1000)
        synchronized(lock) { needKeyFrame = true }
        requestKeyFrame()
    }

    /** Finish the file. Returns true if it holds at least one frame. */
    fun stop(): Boolean {
        if (stopping) return wroteVideo
        stopping = true
        runCatching { venc?.signalEndOfInputStream() }
        audioThread?.join(4000)
        videoThread?.join(4000)
        records.forEach { runCatching { it.stop() }; it.release() }
        runCatching { display?.release() }
        runCatching { venc?.stop() }; runCatching { venc?.release() }
        runCatching { aenc?.stop() }; runCatching { aenc?.release() }
        val ok = synchronized(lock) {
            val good = muxing && wroteVideo
            if (muxing) runCatching { muxer?.stop() }.onFailure { Log.w(TAG, "muxer stop", it) }
            runCatching { muxer?.release() }
            muxer = null; muxing = false
            good
        }
        return ok
    }

    // ------------------------------------------------------------------ video

    private fun startVideo() {
        val format = MediaFormat.createVideoFormat(AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, Plan.bitrate(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A still screen produces no new frames; repeat the last one so players see a steady video.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        val enc = MediaCodec.createEncoderByType(AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        venc = enc
        display = projection.createVirtualDisplay("DynamicIslandScreenRecorder", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        videoThread = Thread({ drain(enc, isVideo = true) }, "screen-video").apply { start() }
    }

    private fun requestKeyFrame() {
        runCatching { venc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    // ------------------------------------------------------------------ audio

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by CaptureActivity before recording with sound
    private fun openAudio(): Boolean {
        val min = AudioRecord.getMinBufferSize(Plan.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { audioUnavailable = true; return false }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Plan.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        fun build(b: AudioRecord.Builder): AudioRecord? = runCatching {
            b.setAudioFormat(fmt).setBufferSizeInBytes(min * 4).build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
        if (audio.usesPlayback) build(AudioRecord.Builder().setAudioPlaybackCaptureConfig(
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()))?.let { records += it }
        if (audio.usesMic) build(AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC))?.let { records += it }
        if (records.isEmpty()) { audioUnavailable = true; return false }
        if (records.size < (if (audio.usesMic) 1 else 0) + (if (audio.usesPlayback) 1 else 0)) audioUnavailable = true
        val format = MediaFormat.createAudioFormat(AAC, Plan.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        aenc = MediaCodec.createEncoderByType(AAC).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        return true
    }

    private fun startAudio() {
        audioThread = Thread({
            val enc = aenc ?: return@Thread
            try {
                records.forEach { it.startRecording() }
                val n = 1024
                val bufs = records.map { ShortArray(n) }
                val mixed = ShortArray(n)
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.nativeOrder())
                var samples = 0L
                val baseUs = System.nanoTime() / 1000
                while (!stopping && !failed) {
                    var got = n
                    records.forEachIndexed { i, r -> got = minOf(got, readFully(r, bufs[i], n)) }
                    if (got <= 0) continue
                    val pcm = if (bufs.size == 2) Mix.add(bufs[0], bufs[1], got, mixed) else bufs[0]
                    bytes.clear(); bytes.asShortBuffer().put(pcm, 0, got)
                    val pts = baseUs + samples * 1_000_000L / Plan.SAMPLE_RATE
                    samples += got
                    feed(enc, bytes, got * 2, pts, eos = false)
                    drain(enc, isVideo = false, untilEos = false)
                }
                feed(enc, null, 0, baseUs + samples * 1_000_000L / Plan.SAMPLE_RATE, eos = true)
                drain(enc, isVideo = false, untilEos = true)
            } catch (e: Exception) { if (!stopping) dropAudio(e) }
        }, "screen-audio").apply { start() }
    }

    private fun readFully(r: AudioRecord, buf: ShortArray, n: Int): Int {
        var off = 0
        while (off < n && !stopping) {
            val k = r.read(buf, off, n - off)
            if (k < 0) return -1
            off += k
        }
        return off
    }

    private fun feed(enc: MediaCodec, data: ByteBuffer?, size: Int, pts: Long, eos: Boolean) {
        val i = enc.dequeueInputBuffer(20_000)
        if (i < 0) return
        val inBuf = enc.getInputBuffer(i) ?: return
        inBuf.clear()
        if (data != null && size > 0) { data.position(0); data.limit(size); inBuf.put(data) }
        enc.queueInputBuffer(i, 0, if (eos) 0 else size, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    // ------------------------------------------------------------------ encoder output → muxer

    private fun drain(enc: MediaCodec, isVideo: Boolean, untilEos: Boolean = true) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!untilEos) return; if (failed) return }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(isVideo, enc.outputFormat)
                    i >= 0 -> {
                        val buf = enc.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null) write(isVideo, buf, info)
                        enc.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: IllegalStateException) {
            if (!stopping) fail("The encoder stopped: ${e.message}")
        }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) { synchronized(lock) {
        val m = muxer ?: return
        if (isVideo) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
        if (videoTrack >= 0 && (!expectAudio || audioTrack >= 0) && !muxing) {
            m.start(); muxing = true
            clock.start(System.nanoTime() / 1000)
            needKeyFrame = true
            requestKeyFrame()
        }
    } }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) { synchronized(lock) {
        val m = muxer ?: return
        if (!muxing) return
        if (isVideo && needKeyFrame) {
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
            needKeyFrame = false
        }
        var t = clock.map(info.presentationTimeUs) ?: return
        val k = if (isVideo) 0 else 1
        if (t <= lastPts[k]) t = lastPts[k] + 1
        lastPts[k] = t
        val copy = MediaCodec.BufferInfo().apply { set(info.offset, info.size, t, info.flags) }
        buf.position(info.offset); buf.limit(info.offset + info.size)
        m.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, copy)
        if (isVideo) wroteVideo = true
    } }

    /** Sound broke: keep recording the picture rather than losing the whole recording. */
    private fun dropAudio(e: Exception) {
        Log.w(TAG, "sound stopped", e)
        audioUnavailable = true
        synchronized(lock) {
            if (!muxing) {
                expectAudio = false
                val m = muxer
                if (m != null && videoTrack >= 0) { m.start(); muxing = true; clock.start(System.nanoTime() / 1000); needKeyFrame = true; requestKeyFrame() }
            }
        }
    }

    private fun fail(msg: String) {
        if (failed || stopping) return
        failed = true
        Log.w(TAG, msg)
        onError(msg)
    }
}
