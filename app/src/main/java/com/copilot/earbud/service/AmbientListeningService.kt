package com.copilot.earbud.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.copilot.earbud.CopilotApplication
import com.copilot.earbud.R
import com.copilot.earbud.audio.AudioCaptureManager
import com.copilot.earbud.audio.EarbudTtsManager
import com.copilot.earbud.data.local.SettingsPreferences
import com.copilot.earbud.data.remote.AiBrainRepository
import com.copilot.earbud.data.remote.SttRepository
import com.copilot.earbud.domain.model.BrainDecision
import com.copilot.earbud.domain.model.PipelineStage
import com.copilot.earbud.domain.model.TtsOutcome
import com.copilot.earbud.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent Foreground Service running with `FOREGROUND_SERVICE_TYPE_MICROPHONE` and a `PARTIAL_WAKE_LOCK`.
 * Continuously listens to ambient Banglish conversations even when the screen is off/locked,
 * transcribes speech chunks via Groq Whisper / Deepgram, filters for objective questions via Gemini / Llama,
 * and whispers single-sentence answers into connected earphones.
 */
class AmbientListeningService : Service() {

    companion object {
        const val ACTION_START = "com.copilot.earbud.action.START"
        const val ACTION_STOP = "com.copilot.earbud.action.STOP"
        const val ACTION_UPDATE_CONFIG = "com.copilot.earbud.action.UPDATE_CONFIG"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: SettingsPreferences
    private val sttRepository = SttRepository()
    private val aiBrainRepository = AiBrainRepository()

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var earbudTtsManager: EarbudTtsManager? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SettingsPreferences(this)

        earbudTtsManager = EarbudTtsManager(
            context = this,
            onEarphoneConnectionChanged = { state ->
                CopilotStateBus.updateEarphoneState(state)
            },
            onLog = { msg ->
                CopilotStateBus.log("TTS", msg)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAmbientCopilot()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                audioCaptureManager?.setVadThreshold(prefs.vadThresholdRms)
                earbudTtsManager?.refreshEarphoneStatus()
                return START_STICKY
            }
            else -> {
                // Pre-flight: refuse to start a microphone pipeline that cannot possibly answer.
                // Stopping before startForeground() is safe and avoids a foreground-service timeout.
                val configIssue = prefs.configIssue()
                if (configIssue != null) {
                    CopilotStateBus.log("CONFIG", "Service not started: $configIssue", isError = true)
                    CopilotStateBus.setPipelineStage(PipelineStage.IDLE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAmbientCopilot()
                return START_STICKY
            }
        }
    }

    private fun startAmbientCopilot() {
        if (CopilotStateBus.isServiceRunning.value) return

        // 1. Promote to Foreground Service with FOREGROUND_SERVICE_TYPE_MICROPHONE (Android 14 / SDK 34+)
        val notification = buildNotification("Listening passively for Banglish questions...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Acquire PARTIAL_WAKE_LOCK so CPU stays awake when screen is locked/off
        acquireWakeLock()

        CopilotStateBus.setServiceRunning(true)
        CopilotStateBus.setPipelineStage(PipelineStage.LISTENING_AMBIENT)
        CopilotStateBus.log("SERVICE", "Ambient Listening Foreground Service started (WakeLock active).")

        // 3. Start AudioRecord + VAD pipeline
        val capture = AudioCaptureManager(
            context = this,
            scope = serviceScope,
            initialRmsThreshold = prefs.vadThresholdRms,
            onRmsUpdate = { rms ->
                CopilotStateBus.updateRms(rms)
            },
            onSpeechStateChanged = { isSpeaking ->
                if (isSpeaking) {
                    CopilotStateBus.setPipelineStage(PipelineStage.SPEECH_DETECTED)
                } else if (CopilotStateBus.pipelineStage.value == PipelineStage.SPEECH_DETECTED) {
                    CopilotStateBus.setPipelineStage(PipelineStage.LISTENING_AMBIENT)
                }
            },
            onUtteranceCaptured = { wavBytes, durationSec ->
                processCapturedUtterance(wavBytes, durationSec)
            },
            onError = { errMsg ->
                CopilotStateBus.setPipelineStage(PipelineStage.ERROR)
                CopilotStateBus.log("AUDIO_ERR", errMsg, isError = true)
            }
        )
        audioCaptureManager = capture
        capture.startCapture()
    }

    private fun processCapturedUtterance(wavBytes: ByteArray, durationSec: Float) {
        serviceScope.launch {
            CopilotStateBus.setPipelineStage(PipelineStage.TRANSCRIBING_STT)
            val sttProvider = prefs.sttProvider
            CopilotStateBus.log(
                "VAD",
                String.format("Speech chunk finalized (%.1fs, %d KB) -> %s", durationSec, wavBytes.size / 1024, sttProvider.name)
            )

            val sttResult = sttRepository.transcribeWav(
                wavBytes = wavBytes,
                provider = sttProvider,
                groqApiKey = prefs.groqApiKey,
                deepgramApiKey = prefs.deepgramApiKey
            )

            val transcript = sttResult.getOrElse { err ->
                CopilotStateBus.log("STT_ERR", err.message ?: "STT failed", isError = true)
                CopilotStateBus.setPipelineStage(PipelineStage.ERROR)
                return@launch
            }

            if (transcript.length < 3) {
                CopilotStateBus.log("STT", "Ignored: transcript too short (${transcript.length} chars).")
                CopilotStateBus.setPipelineStage(PipelineStage.LISTENING_AMBIENT)
                return@launch
            }

            CopilotStateBus.log("STT", "Heard: \"$transcript\"")
            CopilotStateBus.setPipelineStage(PipelineStage.ANALYZING_INTENT)

            val decision = aiBrainRepository.analyzeTranscript(
                transcript = transcript,
                provider = prefs.llmProvider,
                geminiApiKey = prefs.geminiApiKey,
                groqApiKey = prefs.groqApiKey
            ).getOrElse { err ->
                BrainDecision.Failed(err.message ?: "AI brain request failed")
            }

            val answer = when (decision) {
                // A real failure (auth, quota, transport, empty/truncated/blocked response). This is
                // NOT the same thing as "the model decided this was not a question" and is never
                // reported as an [IGNORE].
                is BrainDecision.Failed -> {
                    CopilotStateBus.log("LLM_ERR", decision.message, isError = true)
                    CopilotStateBus.setPipelineStage(PipelineStage.ERROR)
                    return@launch
                }
                // The model deliberately classified this as conversational/social.
                is BrainDecision.Ignored -> {
                    CopilotStateBus.log("FILTER", decision.reason)
                    CopilotStateBus.setPipelineStage(PipelineStage.FILTERED)
                    return@launch
                }
                is BrainDecision.Answer -> decision
            }

            // Objective question detected! Whisper crisp 1-sentence answer into earphones
            CopilotStateBus.log("ANSWER", answer.text, isHighlight = true)
            updateNotification("Answer ready: ${answer.text}")

            CopilotStateBus.setPipelineStage(PipelineStage.WHISPERING_TTS)
            audioCaptureManager?.isPausedForTts = true
            val outcome = try {
                earbudTtsManager?.whisperToEarbuds(
                    text = answer.text,
                    requireEarphones = prefs.requireEarphonesForTts
                ) ?: TtsOutcome.NOT_READY
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                CopilotStateBus.log("TTS_ERR", "Whisper failed: ${t.message}", isError = true)
                TtsOutcome.ENGINE_ERROR
            } finally {
                audioCaptureManager?.isPausedForTts = false
            }

            // Report what actually happened instead of silently resetting the stage.
            when (outcome) {
                TtsOutcome.SPOKEN -> {
                    CopilotStateBus.log("TTS", "Answer spoken into the earbuds.")
                    CopilotStateBus.setPipelineStage(PipelineStage.LISTENING_AMBIENT)
                }
                TtsOutcome.NO_EARPHONES -> {
                    CopilotStateBus.log(
                        "TTS",
                        "Answer NOT spoken: no wired/Bluetooth earphones detected while " +
                            "\"Strict Earbud-Only Whisper\" is ON.",
                        isError = true
                    )
                    CopilotStateBus.setPipelineStage(PipelineStage.TTS_BLOCKED)
                }
                TtsOutcome.NOT_READY -> {
                    CopilotStateBus.log(
                        "TTS",
                        "Answer NOT spoken: the TTS engine is not ready. Check Android Settings > " +
                            "Text-to-speech output and install the Bengali/English voice data.",
                        isError = true
                    )
                    CopilotStateBus.setPipelineStage(PipelineStage.TTS_FAILED)
                }
                TtsOutcome.ENGINE_ERROR -> {
                    CopilotStateBus.setPipelineStage(PipelineStage.TTS_FAILED)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EarbudAmbientCopilot::AmbientMicWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AmbientListeningService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CopilotApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "Stop Copilot", pendingStop)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun stopAmbientCopilot() {
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        releaseWakeLock()
        CopilotStateBus.setServiceRunning(false)
        CopilotStateBus.log("SERVICE", "Ambient Listening Service stopped.")
    }

    override fun onDestroy() {
        stopAmbientCopilot()
        earbudTtsManager?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
