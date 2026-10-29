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
import com.copilot.earbud.domain.model.LogEntry
import com.copilot.earbud.domain.model.PipelineStage
import com.copilot.earbud.domain.model.SttLanguage
import com.copilot.earbud.domain.model.SttProvider
import com.copilot.earbud.domain.model.TtsOutcome
import com.copilot.earbud.domain.model.WhisperMode
import com.copilot.earbud.service.AmbientListeningService
import com.copilot.earbud.service.CopilotStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopilotViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SettingsPreferences.get(application)
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
    private val _keepListeningDuringTts = MutableStateFlow(prefs.keepListeningDuringTts)
    private val _sttLanguage = MutableStateFlow(prefs.sttLanguage)
    private val _whisperMode = MutableStateFlow(prefs.whisperMode)
    private val _batteryOptIgnored = MutableStateFlow(checkBatteryOptimizationIgnored())

    // Debounced persistence (see the key / slider setters below).
    private var groqKeySaveJob: Job? = null
    private var deepgramKeySaveJob: Job? = null
    private var geminiKeySaveJob: Job? = null
    private var vadSaveJob: Job? = null
    private var keepListeningSaveJob: Job? = null

    private var pendingGroqKey: String? = null
    private var pendingDeepgramKey: String? = null
    private var pendingGeminiKey: String? = null

    /**
     * Live values the UI needs but that change many times per second. They are exposed as their own
     * flows so only the tiny composables that display them recompose.
     */
    val logs: StateFlow<List<LogEntry>> = CopilotStateBus.logs
    val rms: StateFlow<Float> = CopilotStateBus.currentRms
    val pipelineStage: StateFlow<PipelineStage> = CopilotStateBus.pipelineStage

    private val providerSelection = combine(
        _sttProvider,
        _llmProvider,
        _groqKeySet,
        _deepgramKeySet,
        _geminiKeySet
    ) { stt, llm, groq, dg, gemini ->
        Tuple5(stt, llm, groq, dg, gemini)
    }

    private val pipelineSettings = combine(
        _vadThreshold,
        _requireEarphones,
        _keepListeningDuringTts,
        _sttLanguage,
        _whisperMode
    ) { vad, earphones, keepListening, language, whisper ->
        Settings5(vad, earphones, keepListening, language, whisper)
    }

    /**
     * Only slow-changing values live here. The activity log, the live RMS level and the pipeline
     * stage are NOT part of it: while they were, every audio frame and every log line recomposed the
     * entire screen and made taps feel unresponsive.
     */
    val uiState: StateFlow<CopilotUiState> = combine(
        CopilotStateBus.isServiceRunning,
        CopilotStateBus.earphoneState,
        providerSelection,
        pipelineSettings,
        _batteryOptIgnored
    ) { running, earphone, providers, settings, batteryIgnored ->
        CopilotUiState(
            isServiceRunning = running,
            earphoneState = earphone,
            sttProvider = providers.a,
            llmProvider = providers.b,
            groqKeySet = providers.c,
            deepgramKeySet = providers.d,
            geminiKeySet = providers.e,
            vadThresholdRms = settings.vad,
            requireEarphonesForTts = settings.earphones,
            keepListeningDuringTts = settings.keepListening,
            sttLanguage = settings.language,
            whisperMode = settings.whisper,
            isBatteryOptimizationIgnored = batteryIgnored
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CopilotUiState()
    )

    // Typing an API key used to encrypt+write the whole encrypted preferences file and rebuild the
    // UI state on every single keystroke, which made the keyboard feel sticky. The "key is set" flag
    // now updates instantly while the encrypted write is debounced and runs off the main thread.
    fun updateGroqApiKey(key: String) {
        pendingGroqKey = key
        updateKeySetFlag(_groqKeySet, key)
        groqKeySaveJob?.cancel()
        groqKeySaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(KEY_SAVE_DEBOUNCE_MS)
            prefs.groqApiKey = key
            pendingGroqKey = null
            _groqKeySet.value = prefs.hasGroqKey
        }
    }

    fun updateDeepgramApiKey(key: String) {
        pendingDeepgramKey = key
        updateKeySetFlag(_deepgramKeySet, key)
        deepgramKeySaveJob?.cancel()
        deepgramKeySaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(KEY_SAVE_DEBOUNCE_MS)
            prefs.deepgramApiKey = key
            pendingDeepgramKey = null
            _deepgramKeySet.value = prefs.hasDeepgramKey
        }
    }

    fun updateGeminiApiKey(key: String) {
        pendingGeminiKey = key
        updateKeySetFlag(_geminiKeySet, key)
        geminiKeySaveJob?.cancel()
        geminiKeySaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(KEY_SAVE_DEBOUNCE_MS)
            prefs.geminiApiKey = key
            pendingGeminiKey = null
            _geminiKeySet.value = prefs.hasGeminiKey
        }
    }

    private fun updateKeySetFlag(target: MutableStateFlow<Boolean>, typed: String) {
        val isSet = typed.isNotBlank()
        if (target.value != isSet) target.value = isSet
    }

    /**
     * Persists any debounced key immediately. Called right before START so the pre-flight key check
     * never sees a stale value (that used to make the START button appear dead when the last
     * character had just been typed).
     */
    private fun flushPendingKeySaves() {
        groqKeySaveJob?.cancel()
        deepgramKeySaveJob?.cancel()
        geminiKeySaveJob?.cancel()
        pendingGroqKey?.let { prefs.groqApiKey = it; pendingGroqKey = null }
        pendingDeepgramKey?.let { prefs.deepgramApiKey = it; pendingDeepgramKey = null }
        pendingGeminiKey?.let { prefs.geminiApiKey = it; pendingGeminiKey = null }
        _groqKeySet.value = prefs.hasGroqKey
        _deepgramKeySet.value = prefs.hasDeepgramKey
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

    /**
     * Called on every slider movement, so it must stay dirt cheap: the value is published to the UI
     * immediately while the preferences write - and the startService() round-trip that applies it to
     * the running pipeline - are debounced. Previously each drag event did both on the main thread.
     */
    fun updateVadThreshold(threshold: Float) {
        if (_vadThreshold.value == threshold) return
        _vadThreshold.value = threshold
        vadSaveJob?.cancel()
        vadSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(CONFIG_SAVE_DEBOUNCE_MS)
            prefs.vadThresholdRms = threshold
            notifyServiceConfigUpdated()
        }
    }

    fun setRequireEarphones(require: Boolean) {
        prefs.requireEarphonesForTts = require
        _requireEarphones.value = require
    }

    /** Mic stays open while the answer is spoken (self-echo is filtered, not the microphone). */
    fun setKeepListeningDuringTts(keep: Boolean) {
        _keepListeningDuringTts.value = keep
        keepListeningSaveJob?.cancel()
        keepListeningSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(CONFIG_SAVE_DEBOUNCE_MS)
            prefs.keepListeningDuringTts = keep
            notifyServiceConfigUpdated()
        }
    }

    fun selectSttLanguage(language: SttLanguage) {
        prefs.sttLanguage = language
        _sttLanguage.value = language
    }

    fun selectWhisperMode(mode: WhisperMode) {
        prefs.whisperMode = mode
        _whisperMode.value = mode
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
            runCatching { context.startService(stopIntent) }
                .onFailure {
                    CopilotStateBus.log(
                        "SERVICE",
                        "Could not deliver the stop request: ${it.message}",
                        isError = true
                    )
                }
        } else {
            // Do not let a debounced key write make the pre-flight check see a stale value.
            flushPendingKeySaves()

            // Pre-flight: do not enter a listening state that is guaranteed to never answer.
            val configIssue = prefs.configIssue()
            if (configIssue != null) {
                CopilotStateBus.log("CONFIG", "Not started: $configIssue", isError = true)
                // Show it in the stage pill too, otherwise a refused start looks like a dead button.
                CopilotStateBus.setPipelineStage(PipelineStage.ERROR)
                Toast.makeText(context, configIssue, Toast.LENGTH_LONG).show()
                return
            }

            val startIntent = Intent(context, AmbientListeningService::class.java).apply {
                action = AmbientListeningService.ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }.onFailure {
                CopilotStateBus.log(
                    "SERVICE",
                    "Could not start the listening service: ${it.message}",
                    isError = true
                )
                CopilotStateBus.setPipelineStage(PipelineStage.ERROR)
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
                    val outcome = withContext(Dispatchers.Default) {
                        ttsManager.whisperToEarbuds(
                            text = decision.text,
                            requireEarphones = prefs.requireEarphonesForTts
                        )
                    }
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

    /** Runs on an IO thread now (debounced), so a background-start failure must not crash. */
    private fun notifyServiceConfigUpdated() {
        if (!CopilotStateBus.isServiceRunning.value) return
        val context = getApplication<Application>()
        val intent = Intent(context, AmbientListeningService::class.java).apply {
            action = AmbientListeningService.ACTION_UPDATE_CONFIG
        }
        runCatching { context.startService(intent) }
            .onFailure { CopilotStateBus.log("SERVICE", "Config update not delivered: ${it.message}") }
    }

    companion object {
        private const val KEY_SAVE_DEBOUNCE_MS = 400L
        private const val CONFIG_SAVE_DEBOUNCE_MS = 250L
    }

    override fun onCleared() {
        // The TTS engine is a process-wide singleton shared with the foreground service, so it is
        // deliberately not shut down when this ViewModel is cleared.
        super.onCleared()
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private data class Settings5<A, B, C, D, E>(
        val vad: A,
        val earphones: B,
        val keepListening: C,
        val language: D,
        val whisper: E
    )
}
