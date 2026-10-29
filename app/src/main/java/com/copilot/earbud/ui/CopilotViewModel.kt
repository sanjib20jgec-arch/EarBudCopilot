package com.copilot.earbud.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copilot.earbud.audio.EarbudTtsManager
import com.copilot.earbud.data.local.SettingsPreferences
import com.copilot.earbud.data.remote.AiBrainRepository
import com.copilot.earbud.domain.model.CopilotUiState
import com.copilot.earbud.domain.model.LlmProvider
import com.copilot.earbud.domain.model.SttProvider
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
    private var previewTtsManager: EarbudTtsManager? = null

    private val _sttProvider = MutableStateFlow(prefs.sttProvider)
    private val _llmProvider = MutableStateFlow(prefs.llmProvider)
    private val _groqKey = MutableStateFlow(prefs.groqApiKey)
    private val _deepgramKey = MutableStateFlow(prefs.deepgramApiKey)
    private val _geminiKey = MutableStateFlow(prefs.geminiApiKey)
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
            groqApiKey = _groqKey.value,
            deepgramApiKey = _deepgramKey.value,
            geminiApiKey = _geminiKey.value,
            vadThresholdRms = _vadThreshold.value,
            requireEarphonesForTts = _requireEarphones.value,
            isBatteryOptimizationIgnored = _batteryOptIgnored.value,
            logs = logs
        )
    }.combine(
        combine(
            _sttProvider,
            _llmProvider,
            _groqKey,
            _deepgramKey,
            _geminiKey
        ) { stt, llm, groq, dg, gemini ->
             Tuple5(stt, llm, groq, dg, gemini)
        }
    ) { base, keys ->
        base.copy(
            sttProvider = keys.a,
            llmProvider = keys.b,
            groqApiKey = keys.c,
            deepgramApiKey = keys.d,
            geminiApiKey = keys.e,
            vadThresholdRms = _vadThreshold.value,
            requireEarphonesForTts = _requireEarphones.value,
            isBatteryOptimizationIgnored = _batteryOptIgnored.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CopilotUiState()
    )

    init {
        previewTtsManager = EarbudTtsManager(
            context = application,
            onEarphoneConnectionChanged = { state ->
                CopilotStateBus.updateEarphoneState(state)
            },
            onLog = { }
        )
    }

    fun updateGroqApiKey(key: String) {
        prefs.groqApiKey = key
        _groqKey.value = key
    }

    fun updateDeepgramApiKey(key: String) {
        prefs.deepgramApiKey = key
        _deepgramKey.value = key
    }

    fun updateGeminiApiKey(key: String) {
        prefs.geminiApiKey = key
        _geminiKey.value = key
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
            val result = aiBrainRepository.analyzeTranscript(
                transcript = sampleTranscript,
                provider = prefs.llmProvider,
                geminiApiKey = prefs.geminiApiKey,
                groqApiKey = prefs.groqApiKey
            )
            result.onSuccess { answer ->
                if (answer == null) {
                    CopilotStateBus.log("BRAIN", "[IGNORE] (Filtered as non-factual/social)")
                } else {
                    CopilotStateBus.log("ANSWER", answer, isHighlight = true)
                    previewTtsManager?.whisperToEarbuds(answer, requireEarphones = prefs.requireEarphonesForTts)
                }
            }.onFailure { err ->
                CopilotStateBus.log("LLM_ERR", err.message ?: "Error", isError = true)
            }
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
        previewTtsManager?.shutdown()
        super.onCleared()
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
