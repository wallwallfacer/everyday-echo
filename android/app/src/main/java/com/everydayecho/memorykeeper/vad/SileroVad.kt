package com.everydayecho.memorykeeper.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Silero VAD v5 (MIT, ~2 MB ONNX) running locally via ONNX Runtime.
 * Feed 512-sample frames of 16 kHz mono PCM16; returns speech probability 0..1.
 * Model I/O: input [1, 64 + 512] float, state [2,1,128], sr int64 -> output [1,1], stateN.
 */
class SileroVad(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open("silero_vad.onnx").use {
        env.createSession(it.readBytes(), OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    }
    private var state = FloatArray(2 * 1 * 128)
    private val ctx = FloatArray(CONTEXT)
    private val input = FloatArray(CONTEXT + FRAME)

    fun reset() {
        state = FloatArray(2 * 1 * 128)
        ctx.fill(0f)
    }

    fun speechProbability(frame: ShortArray): Float {
        require(frame.size == FRAME)
        System.arraycopy(ctx, 0, input, 0, CONTEXT)
        for (i in 0 until FRAME) input[CONTEXT + i] = frame[i] / 32768f
        System.arraycopy(input, FRAME, ctx, 0, CONTEXT)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong())).use { sr ->
                    session.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { out ->
                        val prob = (out[0].value as Array<FloatArray>)[0][0]
                        val newState = out[1].value as Array<Array<FloatArray>>
                        for (a in 0 until 2) System.arraycopy(newState[a][0], 0, state, a * 128, 128)
                        return prob
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 512
        private const val CONTEXT = 64
    }
}
