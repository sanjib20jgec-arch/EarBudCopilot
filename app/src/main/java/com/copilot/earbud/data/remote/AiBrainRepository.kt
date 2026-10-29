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
 * Returns null if the output is `[IGNORE]`, or the crisp one-sentence factual answer if triggered.
 */
class AiBrainRepository {

    companion object {
        const val IGNORE_TOKEN = "[IGNORE]"

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

    suspend fun analyzeTranscript(
        transcript: String,
        provider: LlmProvider,
        geminiApiKey: String,
        groqApiKey: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val rawResponse = when (provider) {
                LlmProvider.GEMINI_FLASH -> queryGeminiFlash(transcript, geminiApiKey)
                LlmProvider.GROQ_LLAMA -> queryGroqLlama(transcript, groqApiKey)
            }.trim()

            if (rawResponse.isEmpty() || rawResponse.contains(IGNORE_TOKEN, ignoreCase = true)) {
                null
            } else {
                rawResponse
            }
        }
    }

    private fun queryGeminiFlash(transcript: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Gemini API key is missing. Enter it in the control panel." }

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
                put("maxOutputTokens", 60)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini HTTP ${response.code}: ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            return json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", IGNORE_TOKEN)
                ?: IGNORE_TOKEN
        }
    }

    private fun queryGroqLlama(transcript: String, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Groq API key is missing. Enter it in the control panel." }

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
            put("model", "llama-3.3-70b-versatile")
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 60)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Groq LLM HTTP ${response.code}: ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            return json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", IGNORE_TOKEN)
                ?: IGNORE_TOKEN
        }
    }
}
