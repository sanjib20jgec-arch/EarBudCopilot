package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Evaluates transcribed ambient speech using the strict Banglish Ambient Copilot System Prompt.
 * Automatically discovers and falls back across active Gemini (e.g. gemini-3.0-flash, gemini-2.5-flash)
 * and Groq (e.g. openai/gpt-oss-120b, openai/gpt-oss-20b, meta-llama/llama-4-scout-17b-16e-instruct) models.
 */
class AiBrainRepository {

    companion object {
        const val IGNORE_TOKEN = "[IGNORE]"

        private val GROQ_FALLBACK_MODELS = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile"
        )

        private val GEMINI_FALLBACK_MODELS = listOf(
            "gemini-3.0-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest"
        )

        val SYSTEM_PROMPT = """
You are a discreet, silent earbud assistant listening to natural ambient conversations spoken in code-mixed Bengali and English (Banglish).

Your tasks:
1. Determine if the recent transcript contains an explicit factual, informational, or objective question (e.g., "কলকাতা থেকে দিঘার দূরত্ব কত?", "Who is the CEO of Google?", "কালকে ইন্ডিয়ার ম্যাচ কার সাথে?").
2. Ignore purely personal, conversational, social, or rhetorical remarks (e.g., "তুই কেমন আছিস?", "চা খাবি?", "আজকে খুব গরম পড়েছে তাই না?").
3. If it is NOT an objective/factual question, output EXACTLY AND ONLY: [IGNORE]
4. If it IS an objective/factual question, provide a factual answer strictly in ONE short, crisp sentence (maximum 12-15 words) in the same conversational language mix (Bengali/Banglish/English). Do not include pleasantries, disclaimers, or conversational filler.
        """.trimIndent()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var resolvedGroqModel: String? = null

    @Volatile
    private var resolvedGeminiModel: String? = null

    suspend fun analyzeTranscript(
        transcript: String,
        provider: LlmProvider,
        geminiApiKey: String,
        groqApiKey: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val rawResponse = when (provider) {
                LlmProvider.GEMINI_FLASH -> queryGeminiWithFallback(transcript, geminiApiKey)
                LlmProvider.GROQ_LLAMA -> queryGroqWithFallback(transcript, groqApiKey)
            }.trim()

            if (rawResponse.isEmpty() || rawResponse.contains(IGNORE_TOKEN, ignoreCase = true)) {
                null
            } else {
                rawResponse
            }
        }
    }

    private fun queryGeminiWithFallback(transcript: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Gemini API key is missing. Tap the top-right settings icon and enter it." }

        val candidates = LinkedHashSet<String>()
        resolvedGeminiModel?.let { candidates.add(it) }
        candidates.addAll(GEMINI_FALLBACK_MODELS)

        var lastError = ""
        for (modelId in candidates) {
            val (reply, err) = tryGeminiGenerateContent(transcript, apiKey, modelId)
            if (reply != null) {
                resolvedGeminiModel = modelId
                return reply
            }
            lastError = err
            // If error message explicitly names the replacement model (e.g. "use models/gemini-3.0-flash"), extract & try it immediately!
            val suggested = Regex("models/(gemini-[a-zA-Z0-9._-]+)").find(err)?.groupValues?.getOrNull(1)
            if (!suggested.isNullOrBlank() && suggested !in candidates) {
                val (sReply, sErr) = tryGeminiGenerateContent(transcript, apiKey, suggested)
                if (sReply != null) {
                    resolvedGeminiModel = suggested
                    return sReply
                }
                lastError = sErr
            }
        }

        // Dynamic fallback: query /v1beta/models to find any active flash/pro model supporting generateContent
        val discovered = fetchAvailableGeminiModels(apiKey)
        for (modelId in discovered) {
            if (modelId in candidates) continue
            val (reply, err) = tryGeminiGenerateContent(transcript, apiKey, modelId)
            if (reply != null) {
                resolvedGeminiModel = modelId
                return reply
            }
            lastError = err
        }

        throw IllegalStateException(lastError)
    }

    private fun tryGeminiGenerateContent(
        transcript: String,
        apiKey: String,
        modelId: String
    ): Pair<String?, String> {
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            })
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", "Recent ambient transcript: \"$transcript\"")))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 100)
            })
        }

        val cleanModel = modelId.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(bodyStr)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", IGNORE_TOKEN)
                    ?: IGNORE_TOKEN
                return text to ""
            }
            return null to "Gemini ($cleanModel) HTTP ${response.code}: ${bodyStr.take(180)}"
        }
    }

    private fun fetchAvailableGeminiModels(apiKey: String): List<String> {
        return runCatching {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                val modelsArr = json.optJSONArray("models") ?: return emptyList()
                val list = mutableListOf<String>()
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").removePrefix("models/")
                    val methods = obj.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                    if (name.startsWith("gemini") &&
                        methods.contains("generateContent") &&
                        !name.contains("embedding") &&
                        !name.contains("vision") &&
                        !name.contains("imagen")
                    ) {
                        list.add(name)
                    }
                }
                // Prefer flash models first for lowest latency
                list.sortedByDescending { it.contains("flash") }
            }
        }.getOrDefault(emptyList())
    }

    private fun queryGroqWithFallback(transcript: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Groq API key is missing. Tap the top-right settings icon and enter it." }

        val candidates = LinkedHashSet<String>()
        resolvedGroqModel?.let { candidates.add(it) }
        candidates.addAll(GROQ_FALLBACK_MODELS)

        var lastError = ""
        for (modelId in candidates) {
            val result = tryGroqChatCompletion(transcript, apiKey, modelId)
            if (result.first != null) {
                resolvedGroqModel = modelId
                return result.first!!
            }
            lastError = result.second
        }

        val discoveredModels = fetchAvailableGroqModels(apiKey)
        for (modelId in discoveredModels) {
            if (modelId in candidates) continue
            val result = tryGroqChatCompletion(transcript, apiKey, modelId)
            if (result.first != null) {
                resolvedGroqModel = modelId
                return result.first!!
            }
            lastError = result.second
        }

        throw IllegalStateException(lastError)
    }

    private fun tryGroqChatCompletion(
        transcript: String,
        apiKey: String,
        modelId: String
    ): Pair<String?, String> {
        val messages = JSONArray()
            .put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            .put(JSONObject().apply {
                put("role", "user")
                put("content", "Recent ambient transcript: \"$transcript\"")
            })

        val payload = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 120)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val json = JSONObject(bodyStr)
                val text = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", IGNORE_TOKEN)
                    ?: IGNORE_TOKEN
                val cleaned = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                return (cleaned.ifEmpty { IGNORE_TOKEN }) to ""
            }
            val err = "Groq ($modelId) HTTP ${response.code}: ${bodyStr.take(160)}"
            return null to err
        }
    }

    private fun fetchAvailableGroqModels(apiKey: String): List<String> {
        return runCatching {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                val data = json.optJSONArray("data") ?: return emptyList()
                val models = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id", "").orEmpty()
                    if (id.isNotBlank() &&
                        !id.contains("whisper", ignoreCase = true) &&
                        !id.contains("tts", ignoreCase = true) &&
                        !id.contains("guard", ignoreCase = true) &&
                        !id.contains("orpheus", ignoreCase = true)
                    ) {
                        models.add(id)
                    }
                }
                models
            }
        }.getOrDefault(emptyList())
    }
}
