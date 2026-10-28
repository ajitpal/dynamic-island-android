package com.ajit.dynamicisland.module.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Silero VAD v4 (MIT, 16 kHz branch as exported by k2-fsa): the probability that a 512-sample frame
 * contains speech. Keeps its recurrent state between frames, so use one instance per recording.
 */
class Vad(modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    private var h = FloatArray(2 * 64)
    private var c = FloatArray(2 * 64)
    private val stateShape = longArrayOf(2, 1, 64)

    fun prob(frame: FloatArray): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, frame.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(c), stateShape).use { ct ->
                    session.run(mapOf("x" to x, "h" to ht, "c" to ct)).use { out ->
                        val p = (out[0] as OnnxTensor).floatBuffer.get(0)
                        h = FloatArray(128).also { (out[1] as OnnxTensor).floatBuffer.get(it) }
                        c = FloatArray(128).also { (out[2] as OnnxTensor).floatBuffer.get(it) }
                        return p
                    }
                }
            }
        }
    }

    fun reset() { h = FloatArray(128); c = FloatArray(128) }
    override fun close() { session.close() }
}
