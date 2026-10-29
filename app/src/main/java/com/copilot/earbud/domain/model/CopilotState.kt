package com.copilot.earbud.domain.model

enum class SttProvider(val displayName: String) {
    GROQ_WHISPER("Groq Whisper (whisper-large-v3)"),
    DEEPGRAM_NOVA2("Deepgram Nova-2 (Multilingual)")
}

enum class LlmProvider(val displayName: String) {
    GEMINI_FLASH("Google Gemini 2.0 Flash"),
    GROQ_LLAMA("Groq Llama 3.3 70B")
}

enum class PipelineStage {
    IDLE,
    LISTENING_AMBIENT,
    SPEECH_DETECTED,
    TRANSCRIBING_STT,
    ANALYZING_INTENT,
    WHISPERING_TTS,
    ERROR
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val isHighlight: Boolean = false,
    val isError: Boolean = false
)

data class EarphoneConnectionState(
    val isConnected: Boolean = false,
    val deviceName: String = "No Earphone Connected",
    val deviceTypeDescription: String = "Connect Wired or Bluetooth Earbuds"
)

data class CopilotUiState(
    val isServiceRunning: Boolean = false,
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val earphoneState: EarphoneConnectionState = EarphoneConnectionState(),
    val currentRmsDb: Float = 0f,
    val sttProvider: SttProvider = SttProvider.GROQ_WHISPER,
    val llmProvider: LlmProvider = LlmProvider.GEMINI_FLASH,
    val groqApiKey: String = "",
    val deepgramApiKey: String = "",
    val geminiApiKey: String = "",
    val vadThresholdRms: Float = 420f,
    val requireEarphonesForTts: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val logs: List<LogEntry> = emptyList()
)
