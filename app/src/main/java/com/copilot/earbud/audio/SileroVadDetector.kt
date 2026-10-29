package com.copilot.earbud.audio

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Hybrid Voice Activity Detector (VAD):
 * - Automatically initializes Silero VAD (`silero_vad.onnx` in `app/src/main/assets/`) via ONNX Runtime Mobile if present.
 * - Seamlessly falls back to a zero-allocation acoustic RMS + Zero-Crossing Rate (ZCR) human voice band detector
 *   if the ONNX model file is not bundled yet, guaranteeing out-of-the-box execution.
 */
class SileroVadDetector(
    context: Context,
    @Volatile var rmsThreshold: Float = 420f
) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var hState = Array(2) { Array(1) { FloatArray(64) } }
    private var cState = Array(2) { Array(1) { FloatArray(64) } }

    val isUsingOnnxModel: Boolean
        get() = ortSession != null

    init {
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains("silero_vad.onnx")) {
                val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
                ortEnv = OrtEnvironment.getEnvironment()
                ortSession = ortEnv?.createSession(modelBytes, OrtSession.SessionOptions())
            }
        } catch (_: Exception) {
            ortSession = null
        }
    }

    /**
     * Evaluates a 512-sample (32ms @ 16kHz) PCM frame and returns Pair(isSpeech, rmsAmplitude).
     */
    fun evaluateFrame(pcmFrame: ShortArray, length: Int): Pair<Boolean, Float> {
        if (length <= 0) return false to 0f

        // Compute RMS energy
        var sumSquares = 0.0
        var zeroCrossings = 0
        for (i in 0 until length) {
            val sample = pcmFrame[i].toDouble()
            sumSquares += sample * sample
            if (i > 0 && ((pcmFrame[i] >= 0 && pcmFrame[i - 1] < 0) || (pcmFrame[i] < 0 && pcmFrame[i - 1] >= 0))) {
                zeroCrossings++
            }
        }
        val rms = sqrt(sumSquares / length).toFloat()

        // If Silero ONNX session is loaded and frame >= 512 samples, run neural VAD
        val session = ortSession
        val env = ortEnv
        if (session != null && env != null && length >= 512) {
            try {
                val floatSamples = FloatArray(512) { idx -> pcmFrame[idx] / 32768.0f }
                val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatSamples), longArrayOf(1, 512))
                val srTensor = OnnxTensor.createTensor(env, longArrayOf(16000L))
                val hTensor = OnnxTensor.createTensor(env, hState)
                val cTensor = OnnxTensor.createTensor(env, cState)

                val inputs = mapOf(
                    "input" to inputTensor,
                    "sr" to srTensor,
                    "h" to hTensor,
                    "c" to cTensor
                )
                session.run(inputs).use { results ->
                    val probOutput = results[0].value as Array<FloatArray>
                    val speechProb = probOutput[0][0]
                    @Suppress("UNCHECKED_CAST")
                    hState = results[1].value as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    cState = results[2].value as Array<Array<FloatArray>>
                    return (speechProb >= 0.55f) to rms
                }
            } catch (_: Exception) {
                // Fallback to acoustic energy + ZCR
            }
        }

        // Lightweight acoustic energy + Zero-Crossing Rate human speech filter.
        // The band is deliberately wide: the previous 0.015 lower bound rejected deep male voices
        // (a ~100 Hz fundamental over a 32 ms frame yields ~0.0125), which silently dropped
        // utterances before they ever reached transcription. RMS still gates absolute level.
        val zcrRatio = zeroCrossings.toFloat() / length.toFloat()
        val isPlausibleVoiceFrequency = zcrRatio in 0.006f..0.55f
        val isSpeech = rms >= rmsThreshold && isPlausibleVoiceFrequency
        return isSpeech to rms
    }

    fun close() {
        runCatching { ortSession?.close() }
        runCatching { ortEnv?.close() }
    }
}
