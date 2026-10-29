package com.copilot.earbud.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Continuously records 16kHz 16-bit Mono PCM audio from the microphone.
 * Uses a pre-roll ring buffer (400ms) + speech accumulator (2s to 12s max) + 800ms silence tail detector
 * to slice natural utterances and dispatch in-memory WAV chunks to the STT worker.
 */
class AudioCaptureManager(
    context: Context,
    private val scope: CoroutineScope,
    initialRmsThreshold: Float,
    private val onRmsUpdate: (Float) -> Unit,
    private val onSpeechStateChanged: (Boolean) -> Unit,
    private val onUtteranceCaptured: suspend (ByteArray, Float, Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 512 // 32ms per frame at 16kHz
        private const val PRE_ROLL_FRAMES = 12 // ~384ms pre-roll before speech trigger
        // 800ms of trailing silence used to be spent before transcription even started; 550ms is
        // still comfortably longer than a natural inter-word pause but cuts ~250ms off every answer.
        private const val SILENCE_TAIL_MS = 550L
        private const val MIN_SPEECH_DURATION_MS = 650L // Ignore tiny clicks/coughs < 650ms
        private const val MAX_SPEECH_DURATION_MS = 10000L // Force-dispatch chunk at 10 seconds
        private const val SPEECH_VOTE_WINDOW = 3 // 2-of-3 frames must agree before a state change
        private const val SPEECH_VOTE_MIN = 2
    }

    private val vadDetector = SileroVadDetector(context, initialRmsThreshold)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    /**
     * True when we are keeping the mic open while the answer is spoken (so surrounding voices are
     * not missed). When false the old behaviour is used: capture is suspended during TTS.
     */
    @Volatile
    var keepListeningDuringTts: Boolean = true

    /** Set by the service while the answer is being spoken. */
    @Volatile
    var ttsSpeaking: Boolean = false

    fun setVadThreshold(threshold: Float) {
        vadDetector.rmsThreshold = threshold
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (captureJob?.isActive == true) return

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            onError("AudioRecord hardware initialization failed (invalid buffer size: $minBufSize)")
            return
        }

        val bufferSizeInBytes = max(minBufSize * 2, FRAME_SAMPLES * 2 * 10)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        } catch (e: Exception) {
            onError("Failed to initialize AudioRecord: ${e.message}")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            onError("Microphone AudioRecord state not initialized.")
            recorder.release()
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        echoCanceler = enableEchoCancellation(recorder)

        captureJob = scope.launch(Dispatchers.IO) {
            val frameBuffer = ShortArray(FRAME_SAMPLES)
            val preRollQueue = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
            val speechVotes = ArrayDeque<Boolean>(SPEECH_VOTE_WINDOW)
            val activeSpeechPcm = ArrayList<Short>(SAMPLE_RATE * 6)

            var isCurrentlySpeaking = false
            var utteranceOverlapsTts = false
            var speechStartTimestampMs = 0L
            var lastVoiceTimestampMs = 0L
            var uiRmsThrottleCounter = 0

            try {
                while (isActive) {
                    val readCount = recorder.read(frameBuffer, 0, FRAME_SAMPLES)
                    if (readCount <= 0) continue

                    // While the answer is being spoken we normally KEEP capturing, so a question
                    // asked during the reply is not lost. The utterance is flagged and the service
                    // discards it if it turns out to be our own TTS leaking back into the mic.
                    val speakingNow = ttsSpeaking
                    if (speakingNow) {
                        utteranceOverlapsTts = true
                    }
                    if (speakingNow && !keepListeningDuringTts) {
                        if (isCurrentlySpeaking) {
                            isCurrentlySpeaking = false
                            activeSpeechPcm.clear()
                            onSpeechStateChanged(false)
                        }
                        utteranceOverlapsTts = false
                        continue
                    }

                    val frameCopy = frameBuffer.copyOf(readCount)
                    val (isSpeechFrame, rms) = vadDetector.evaluateFrame(frameCopy, readCount)

                    // Hysteresis: a single noisy/marginal frame can no longer start or break an
                    // utterance, which previously split questions into pieces or dropped them.
                    speechVotes.addLast(isSpeechFrame)
                    while (speechVotes.size > SPEECH_VOTE_WINDOW) speechVotes.removeFirst()
                    val isSpeech = speechVotes.count { it } >= SPEECH_VOTE_MIN

                    if (++uiRmsThrottleCounter % 3 == 0) {
                        onRmsUpdate(rms)
                    }

                    val now = System.currentTimeMillis()

                    if (isSpeech) {
                        if (!isCurrentlySpeaking) {
                            isCurrentlySpeaking = true
                            speechStartTimestampMs = now
                            utteranceOverlapsTts = speakingNow
                            onSpeechStateChanged(true)

                            // Prepend pre-roll frames so the first consonant of a Banglish word isn't clipped
                            activeSpeechPcm.clear()
                            for (preFrame in preRollQueue) {
                                for (s in preFrame) activeSpeechPcm.add(s)
                            }
                            preRollQueue.clear()
                        }
                        lastVoiceTimestampMs = now
                        for (s in frameCopy) activeSpeechPcm.add(s)

                        // Force-finalize if continuous speech reaches max window (10s)
                        if (now - speechStartTimestampMs >= MAX_SPEECH_DURATION_MS) {
                            finalizeAndDispatch(activeSpeechPcm, now - speechStartTimestampMs, utteranceOverlapsTts)
                            isCurrentlySpeaking = false
                            onSpeechStateChanged(false)
                        }
                    } else {
                        if (isCurrentlySpeaking) {
                            for (s in frameCopy) activeSpeechPcm.add(s)
                            val silenceElapsed = now - lastVoiceTimestampMs
                            if (silenceElapsed >= SILENCE_TAIL_MS) {
                                val totalSpeechDuration = lastVoiceTimestampMs - speechStartTimestampMs
                                if (totalSpeechDuration >= MIN_SPEECH_DURATION_MS) {
                                    finalizeAndDispatch(activeSpeechPcm, totalSpeechDuration, utteranceOverlapsTts)
                                } else {
                                    activeSpeechPcm.clear()
                                }
                                isCurrentlySpeaking = false
                                onSpeechStateChanged(false)
                            }
                        } else {
                            if (preRollQueue.size >= PRE_ROLL_FRAMES) {
                                preRollQueue.removeFirst()
                            }
                            preRollQueue.addLast(frameCopy)
                        }
                    }
                }
            } catch (e: Exception) {
                onError("Audio capture loop error: ${e.message}")
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
            }
        }
    }

    private suspend fun finalizeAndDispatch(
        pcmList: ArrayList<Short>,
        durationMs: Long,
        overlapsTts: Boolean
    ) {
        if (pcmList.isEmpty()) return
        val shorts = ShortArray(pcmList.size)
        for (i in pcmList.indices) {
            shorts[i] = pcmList[i]
        }
        pcmList.clear()
        val wavBytes = WavEncoder.encodePcm16MonoToWav(shorts, SAMPLE_RATE)
        val durationSec = durationMs / 1000f
        onUtteranceCaptured(wavBytes, durationSec, overlapsTts)
    }

    private fun enableEchoCancellation(recorder: AudioRecord): AcousticEchoCanceler? = try {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        // Release the recorder deterministically instead of waiting for the coroutine to unwind,
        // so the microphone is not left open after the service stops.
        runCatching {
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
        }
        runCatching {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        }
        echoCanceler = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { vadDetector.close() }
    }
}
