package com.copilot.earbud.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.copilot.earbud.domain.model.LlmProvider
import com.copilot.earbud.domain.model.SttProvider

/**
 * Persistent settings.
 *
 * API keys are kept in [EncryptedSharedPreferences] (AES256-GCM values, AES256-SIV keys, backed by
 * the Android Keystore) instead of a plaintext XML file. Keys written by older builds are migrated
 * once and then removed from the plaintext store. If the Keystore is unavailable the class falls
 * back to private SharedPreferences so the app still runs instead of crashing on start.
 */
class SettingsPreferences(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences = createPreferences()

    private fun createPreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encrypted = EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyKeys(encrypted)
            encrypted
        } catch (t: Throwable) {
            Log.w(TAG, "Encrypted storage unavailable, falling back to private preferences.", t)
            appContext.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /** Moves API keys from the pre-1.1 plaintext file into the encrypted store, then wipes them. */
    private fun migrateLegacyKeys(target: SharedPreferences) {
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        val apiKeyNames = listOf(KEY_GROQ_API, KEY_DEEPGRAM_API, KEY_GEMINI_API)

        val editor = target.edit()
        var moved = 0
        for (name in apiKeyNames) {
            val value = legacy.getString(name, null) ?: continue
            if (value.isBlank()) continue
            editor.putString(name, value)
            moved++
        }
        editor.apply()

        if (moved > 0) {
            val cleanup = legacy.edit()
            apiKeyNames.forEach { cleanup.remove(it) }
            cleanup.apply()
            // Never log key material - only the count.
            Log.i(TAG, "Migrated $moved API key(s) from plaintext to encrypted storage.")
        }
    }

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ_API, value.trim()).apply()

    var deepgramApiKey: String
        get() = prefs.getString(KEY_DEEPGRAM_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEEPGRAM_API, value.trim()).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API, value.trim()).apply()

    val hasGroqKey: Boolean
        get() = groqApiKey.isNotBlank()

    val hasDeepgramKey: Boolean
        get() = deepgramApiKey.isNotBlank()

    val hasGeminiKey: Boolean
        get() = geminiApiKey.isNotBlank()

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

    /** True while the user expects the copilot to be listening; used to offer a resume after reboot. */
    var resumeAfterBoot: Boolean
        get() = prefs.getBoolean(KEY_RESUME_AFTER_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_AFTER_BOOT, value).apply()

    /**
     * Pre-flight validation: returns a human readable problem with the current pipeline
     * configuration, or `null` when everything the pipeline needs is present.
     *
     * Either provider can satisfy each stage (Groq supports both Whisper STT and chat, Deepgram is
     * STT-only, Gemini is chat-only), and the repositories fall back to the other provider when the
     * preferred one fails - so a single Groq key is enough to run the whole pipeline.
     */
    fun configIssue(): String? {
        if (!hasGroqKey && !hasDeepgramKey) {
            return "Speech-to-text needs a Groq or Deepgram API key. Add one in API Keys & Pipeline Engine."
        }
        if (!hasGeminiKey && !hasGroqKey) {
            return "AI brain needs a Gemini or Groq API key. Add one in API Keys & Pipeline Engine."
        }
        return null
    }

    companion object {
        private const val TAG = "SettingsPreferences"

        private const val LEGACY_PREFS_FILE = "earbud_copilot_prefs"
        private const val ENCRYPTED_PREFS_FILE = "earbud_copilot_secure_prefs"

        private const val KEY_GROQ_API = "groq_api_key"
        private const val KEY_DEEPGRAM_API = "deepgram_api_key"
        private const val KEY_GEMINI_API = "gemini_api_key"
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_VAD_THRESHOLD = "vad_threshold_rms"
        private const val KEY_REQUIRE_EARPHONES = "require_earphones_tts"
        private const val KEY_RESUME_AFTER_BOOT = "resume_after_boot"
    }
}
