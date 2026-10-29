package com.copilot.earbud.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copilot.earbud.audio.EarbudTtsManager
import com.copilot.earbud.data.local.SettingsPreferences
import com.copilot.earbud.data.remote.AiBrainRepository
import com.copilot.earbud.domain.model.BrainDecision
import com.copilot.earbud.domain.model.CopilotUiState
import com.copilot.earbud.domain.model.LlmProvider
import com.copilot.earbud.domain.model.SttProvider
import com.copilot.earbud.domain.model.TtsOutcome
import com.copilot.earbud.service.AmbientListeningService
import com.copilot.earbud.service.CopilotStateBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CopilotViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SettingsPreferences(application)
    private val aiBrainRepository = AiBrainRepository()

    /**
     * Shared process-wide TTS engine. Created eagerly so the engine is initializing while the user
     * is still entering keys, and shared with [AmbientListeningService] so there is exactly one
     * `TextToSpeech` binding in the process.
     */
    private val ttsManager: EarbudTtsManager = EarbudTtsManager.get(application)

    private val _sttProvider = MutableStateFlow(prefs.sttProvider)
    private val _llmProvider = MutableStateFlow(prefs.llmProvider)

    // Only "is a key stored?" ever leaves this class - key material is never exposed to the UI layer.
    private val _groqKeySet = MutableStateFlow(prefs.hasGroqKey)
    private val _deepgramKeySet = MutableStateFlow(prefs.hasDeepgramKey)
    private val _geminiKeySet = MutableStateFlow(prefs.hasGeminiKey)
    private val _vadThreshold = MutableStateFlow(prefs.vadThresholdRms)
    private val _requireEarphones = MutableStateFlow(prefs.requireEarphonesForTts)
    private val _batteryOptIgnored = MutableStateFlow(checkBatteryOptimizationIgnored())

    val uiState: StateFlow<CopilotUiState> = combine(
        CopilotStateBus.isServiceRunning,
        CopilotStateBus.pipelineStage,
        CopilotStateBus.earphoneState,
        CopilotStateBus.currentRms,
        CopilotStateBus.logs
    ) { running, stage, earphone, rms, logs ->
        CopilotUiState(
            isServiceRunning = running,
            pipelineStage = stage,
            earphoneState = earphone,
            currentRmsDb = rms,
            sttProvider = _sttProvider.value,
            llmProvider = _llmProvider.value,
            groqKeySet = _groqKeySet.value,
            deepgramKeySet = _deepgramKeySet.value,
            geminiKeySet = _geminiKeySet.value,
            vadThresholdRms = _vadThreshold.value,
            requireEarphonesForTts = _requireEarphones.value,
            isBatteryOptimizationIgnored = _batteryOptIgnored.value,
            logs = logs
        )
    }.combine(
        combine(
            _sttProvider,
            _llmProvider,
            _groqKeySet,
            _deepgramKeySet,
            _geminiKeySet
        ) { stt, llm, groq, dg, gemini ->
             Tuple5(stt, llm, groq, dg, gemini)
        }
    ) { base, keys ->
        base.copy(
            sttProvider = keys.a,
            llmProvider = keys.b,
            groqKeySet = keys.c,
            deepgramKeySet = keys.d,
            geminiKeySet = keys.e,
            vadThresholdRms = _vadThreshold.value,
            requireEarphonesForTts = _requireEarphones.value,
            isBatteryOptimizationIgnored = _batteryOptIgnored.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CopilotUiState()
    )

    fun updateGroqApiKey(key: String) {
        prefs.groqApiKey = key
        _groqKeySet.value = prefs.hasGroqKey
    }

    fun updateDeepgramApiKey(key: String) {
        prefs.deepgramApiKey = key
        _deepgramKeySet.value = prefs.hasDeepgramKey
    }

    fun updateGeminiApiKey(key: String) {
        prefs.geminiApiKey = key
        _geminiKeySet.value = prefs.hasGeminiKey
    }

    fun selectSttProvider(provider: SttProvider) {
        prefs.sttProvider = provider
        _sttProvider.value = provider
    }

    fun selectLlmProvider(provider: LlmProvider) {
        prefs.llmProvider = provider
        _llmProvider.value = provider
    }

    fun updateVadThreshold(threshold: Float) {
        prefs.vadThresholdRms = threshold
        _vadThreshold.value = threshold
        notifyServiceConfigUpdated()
    }

    fun setRequireEarphones(require: Boolean) {
        prefs.requireEarphonesForTts = require
        _requireEarphones.value = require
    }

    fun refreshBatteryOptimizationState() {
        _batteryOptIgnored.value = checkBatteryOptimizationIgnored()
    }

    private fun checkBatteryOptimizationIgnored(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun toggleService() {
        val context = getApplication<Application>()
        if (CopilotStateBus.isServiceRunning.value) {
            val stopIntent = Intent(context, AmbientListeningService::class.java).apply {
                action = AmbientListeningService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            // Pre-flight: do not enter a listening state that is guaranteed to never answer.
            val configIssue = prefs.configIssue()
            if (configIssue != null) {
                CopilotStateBus.log("CONFIG", "Not started: $configIssue", isError = true)
                Toast.makeText(context, configIssue, Toast.LENGTH_LONG).show()
                return
            }

            val startIntent = Intent(context, AmbientListeningService::class.java).apply {
                action = AmbientListeningService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    /**
     * Allows testing the Banglish AI Brain + Earbud TTS directly from the UI with a sample phrase.
     */
    fun simulateBanglishTranscript(sampleTranscript: String) {
        viewModelScope.launch {
            CopilotStateBus.log("SIMULATE", "Testing transcript: \"$sampleTranscript\"")

            val decision = aiBrainRepository.analyzeTranscript(
                transcript = sampleTranscript,
                provider = prefs.llmProvider,
                geminiApiKey = prefs.geminiApiKey,
                groqApiKey = prefs.groqApiKey
            ).getOrElse { err ->
                CopilotStateBus.log("LLM_ERR", err.message ?: "Error", isError = true)
                return@launch
            }

            when (decision) {
                is BrainDecision.Failed -> {
                    CopilotStateBus.log("LLM_ERR", decision.message, isError = true)
                }
                is BrainDecision.Ignored -> {
                    CopilotStateBus.log("FILTER", decision.reason)
                }
                is BrainDecision.Answer -> {
                    CopilotStateBus.log("ANSWER", decision.text, isHighlight = true)
                    val outcome = ttsManager.whisperToEarbuds(
                        text = decision.text,
                        requireEarphones = prefs.requireEarphonesForTts
                    )
                    CopilotStateBus.log(
                        "TTS",
                        when (outcome) {
                            TtsOutcome.SPOKEN -> "Test answer spoken."
                            TtsOutcome.NO_EARPHONES -> "Test answer NOT spoken: no earphones detected."
                            TtsOutcome.NOT_READY -> "Test answer NOT spoken: TTS engine not ready."
                            TtsOutcome.ENGINE_ERROR -> "Test answer NOT spoken: TTS engine error."
                        },
                        isError = outcome != TtsOutcome.SPOKEN
                    )
                }
            }
        }
    }

    /**
     * Self-check that answers "why is nothing being spoken?" without a PC: reports TTS engine
     * readiness, earphone detection and whether each provider key actually authenticates.
     */
    fun runDiagnostics() {
        viewModelScope.launch {
            CopilotStateBus.log("DIAG", "Running diagnostics...")

            CopilotStateBus.log(
                "DIAG",
                if (ttsManager.isReady) "TTS: engine ready." else
                    "TTS: engine NOT ready - check Android Settings > Text-to-speech output (install a Bengali voice for Banglish answers)."
            )

            val earphones = ttsManager.refreshEarphoneStatus()
            CopilotStateBus.log(
                "DIAG",
                if (earphones.isConnected) {
                    "Earphones: ${earphones.deviceName} (${earphones.deviceTypeDescription})."
                } else {
                    "Earphones: none detected - \"Strict Earbud-Only Whisper\" will block all speech."
                }
            )

            val sttKeyStatus = when (prefs.sttProvider) {
                SttProvider.GROQ_WHISPER -> if (prefs.hasGroqKey) "present" else "MISSING"
                SttProvider.DEEPGRAM_NOVA2 -> if (prefs.hasDeepgramKey) "present" else "MISSING"
            }
            CopilotStateBus.log("DIAG", "Speech-to-text: ${prefs.sttProvider.displayName} key $sttKeyStatus.")

            aiBrainRepository.pingProviders(
                geminiApiKey = prefs.geminiApiKey,
                groqApiKey = prefs.groqApiKey
            ).forEach { line -> CopilotStateBus.log("DIAG", line) }
        }
    }

    fun clearLogs() {
        CopilotStateBus.clearLogs()
    }

    private fun notifyServiceConfigUpdated() {
        if (!CopilotStateBus.isServiceRunning.value) return
        val context = getApplication<Application>()
        val intent = Intent(context, AmbientListeningService::class.java).apply {
            action = AmbientListeningService.ACTION_UPDATE_CONFIG
        }
        context.startService(intent)
    }

    override fun onCleared() {
        // The TTS engine is a process-wide singleton shared with the foreground service, so it is
        // deliberately not shut down when this ViewModel is cleared.
        super.onCleared()
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
