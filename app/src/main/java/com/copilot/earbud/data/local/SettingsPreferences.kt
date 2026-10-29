package com.copilot.earbud.data.local

import android.content.Context
import android.content.SharedPreferences
import com.copilot.earbud.domain.model.LlmProvider
import com.copilot.earbud.domain.model.SttProvider

class SettingsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("earbud_copilot_prefs", Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_API, value.trim()).apply()

    var deepgramApiKey: String
        get() = prefs.getString(KEY_DEEPGRAM_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEEPGRAM_API, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API, value.trim()).apply()

    var sttProvider: SttProvider
        get() {
            val name = prefs.getString(KEY_STT_PROVIDER, SttProvider.GROQ_WHISPER.name)
            return runCatching { SttProvider.valueOf(name!!) }.getOrDefault(SttProvider.GROQ_WHISPER)
        }
        set(value) = prefs.edit().putString(KEY_STT_PROVIDER, value.name).apply()

    var llmProvider: LlmProvider
        get() {
            val name = prefs.getString(KEY_LLM_PROVIDER, LlmProvider.GEMINI_FLASH.name)
            return runCatching { LlmProvider.valueOf(name!!) }.getOrDefault(LlmProvider.GEMINI_FLASH)
        }
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value.name).apply()

    var vadThresholdRms: Float
        get() = prefs.getFloat(KEY_VAD_THRESHOLD, 420f)
        set(value) = prefs.edit().putFloat(KEY_VAD_THRESHOLD, value).apply()

    var requireEarphonesForTts: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_EARPHONES, true)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_EARPHONES, value).apply()

    companion object {
        private const val KEY_GROQ_API = "groq_api_key"
        private const val KEY_DEEPGRAM_API = "deepgram_api_key"
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_VAD_THRESHOLD = "vad_threshold_rms"
        private const val KEY_REQUIRE_EARPHONES = "require_earphones_tts"
    }
}
