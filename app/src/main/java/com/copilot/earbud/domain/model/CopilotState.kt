package com.copilot.earbud.domain.model

enum class SttProvider(val displayName: String) {
    GROQ_WHISPER("Groq Whisper (whisper-large-v3)"),
    DEEPGRAM_NOVA2("Deepgram Nova-2 (Multilingual)")
}

/** Optional language hint for speech-to-text. Auto lets the provider detect Banglish itself. */
enum class SttLanguage(val apiCode: String, val displayName: String, val chipLabel: String) {
    AUTO("", "Auto detect", "Auto"),
    BENGALI("bn", "Bengali (bn)", "Bengali"),
    ENGLISH("en", "English (en)", "English")
}

/** Whisper model preference: turbo is ~2x faster, large-v3 is slightly more accurate. */
enum class WhisperMode(val displayName: String, val chipLabel: String) {
    FAST("Fast (large-v3-turbo)", "Fast"),
    ACCURATE("Accurate (large-v3)", "Accurate")
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

    /** The AI brain deliberately decided the transcript is not a factual question (a real `[IGNORE]`). */
    FILTERED,

    /** An answer was produced but intentionally not spoken (e.g. no earphones + strict privacy mode). */
    TTS_BLOCKED,

    /** An answer was produced and speech output failed (engine not ready / engine error). */
    TTS_FAILED,

    /** A stage failed: STT/LLM transport, auth, quota, truncation or blocked response. */
    ERROR
}

/**
 * Outcome of the AI brain for one transcript.
 *
 * The distinction between [Ignored] and [Failed] is essential: before this existed, an empty,
 * truncated or safety-blocked provider response was reported as `[IGNORE]` and silently dropped,
 * which is indistinguishable from a correct "this was not a question" decision.
 */
sealed class BrainDecision {

    /** A spoken-worthy answer was generated. */
    data class Answer(val text: String, val model: String) : BrainDecision()

    /** The provider explicitly classified the transcript as non-factual / conversational. */
    data class Ignored(val reason: String) : BrainDecision()

    /** No usable answer was produced (HTTP error, auth, quota, empty, truncated or blocked response). */
    data class Failed(val message: String) : BrainDecision()
}

/** Result of a text-to-speech attempt, so callers can tell *why* nothing was heard. */
enum class TtsOutcome {
    SPOKEN,
    NOT_READY,
    NO_EARPHONES,
    ENGINE_ERROR
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

/**
 * Everything the control screen renders *that changes rarely*.
 *
 * Deliberately excludes the live mic level and the activity log: those update ~10x/second and used
 * to be part of this object, which forced the whole screen (dozens of chips, switches, text fields)
 * to recompose on every audio frame - the main reason the UI felt laggy and taps were dropped.
 * They are exposed as their own flows and collected only by the two small composables that show
 * them. [PipelineStage] is likewise kept out and collected by the status card alone.
 */
data class CopilotUiState(
    val isServiceRunning: Boolean = false,
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val earphoneState: EarphoneConnectionState = EarphoneConnectionState(),
    val sttProvider: SttProvider = SttProvider.GROQ_WHISPER,
    val llmProvider: LlmProvider = LlmProvider.GEMINI_FLASH,

    // Key material is never carried in UI state; only whether a key is stored.
    val groqKeySet: Boolean = false,
    val deepgramKeySet: Boolean = false,
    val geminiKeySet: Boolean = false,
    val vadThresholdRms: Float = 420f,
    val requireEarphonesForTts: Boolean = true,

    /** Keep the microphone open while the answer is being spoken. */
    val keepListeningDuringTts: Boolean = true,
    val sttLanguage: SttLanguage = SttLanguage.AUTO,
    val whisperMode: WhisperMode = WhisperMode.FAST,
    val isBatteryOptimizationIgnored: Boolean = false
)
