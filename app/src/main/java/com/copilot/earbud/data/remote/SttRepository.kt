package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.SttProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speech-to-Text Repository supporting:
 * 1. Groq Whisper (`whisper-large-v3-turbo` / `whisper-large-v3` auto-fallback) via multipart/form-data
 * 2. Deepgram Nova-2 (`nova-2`) via raw audio/wav binary POST
 */
class SttRepository {

    companion object {
        private val GROQ_WHISPER_MODELS = listOf(
            "whisper-large-v3-turbo",
            "whisper-large-v3"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var resolvedWhisperModel: String? = null

    suspend fun transcribeWav(
        wavBytes: ByteArray,
        provider: SttProvider,
        groqApiKey: String,
        deepgramApiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(transcribeWithFallback(wavBytes, provider, groqApiKey, deepgramApiKey))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Tries the selected provider first, then any other provider that has a key. This is what makes
     * "only a Groq key" or "only a Deepgram key" a working configuration instead of a silent
     * listening session that never answers.
     */
    private fun transcribeWithFallback(
        wavBytes: ByteArray,
        provider: SttProvider,
        groqApiKey: String,
        deepgramApiKey: String
    ): String {
        val order = LinkedHashSet<SttProvider>()
        order.add(provider)
        order.addAll(SttProvider.entries)

        val errors = mutableListOf<String>()
        for (candidate in order) {
            val key = when (candidate) {
                SttProvider.GROQ_WHISPER -> groqApiKey
                SttProvider.DEEPGRAM_NOVA2 -> deepgramApiKey
            }
            if (key.isBlank()) {
                errors += "${candidate.displayName}: no API key"
                continue
            }
            val transcript = try {
                when (candidate) {
                    SttProvider.GROQ_WHISPER -> transcribeWithGroqWhisper(wavBytes, key)
                    SttProvider.DEEPGRAM_NOVA2 -> transcribeWithDeepgram(wavBytes, key)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                errors += (t.message ?: "${candidate.displayName}: failed")
                continue
            }
            return transcript
        }

        throw IllegalStateException("Speech-to-text failed. " + errors.joinToString(" | "))
    }

    /** One retry for throttling / transient server errors (a 429 used to end the utterance silently). */
    private fun executeWithRetry(request: Request, maxRetries: Int = 1): Response {
        var attempt = 0
        while (true) {
            val response = httpClient.newCall(request).execute()
            val retryable = response.code == 429 || response.code == 500 ||
                response.code == 502 || response.code == 503 || response.code == 504
            if (!retryable || attempt >= maxRetries) return response
            response.close()
            attempt++
            runCatching { Thread.sleep(700L * attempt) }
        }
    }

    private fun transcribeWithGroqWhisper(wavBytes: ByteArray, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Groq API key is missing. Tap the top-right settings icon and enter it." }

        val candidates = LinkedHashSet<String>()
        resolvedWhisperModel?.let { candidates.add(it) }
        candidates.addAll(GROQ_WHISPER_MODELS)

        var lastError = ""
        for (modelId in candidates) {
            val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "ambient_chunk.wav", wavBody)
                .addFormDataPart("model", modelId)
                .addFormDataPart(
                    "prompt",
                    "Natural conversation in code-mixed Bengali and English (Banglish). কলকাতা থেকে দিঘার দূরত্ব কত? Who is the CEO of Google? কালকে ইন্ডিয়ার ম্যাচ কার সাথে?"
                )
                .addFormDataPart("temperature", "0.0")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(multipartBody)
                .build()

            executeWithRetry(request).use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    resolvedWhisperModel = modelId
                    val json = JSONObject(bodyStr)
                    return json.optString("text", "").trim()
                }
                lastError = "Groq STT ($modelId) HTTP ${response.code}: ${bodyStr.take(160)}"
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun transcribeWithDeepgram(wavBytes: ByteArray, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Deepgram API key is missing. Tap the top-right settings icon and enter it." }

        val requestBody = wavBytes.toRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true&detect_language=true")
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        executeWithRetry(request).use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Deepgram STT HTTP ${response.code}: ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            val transcript = json
                .optJSONObject("results")
                ?.optJSONArray("channels")
                ?.optJSONObject(0)
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optString("transcript", "")
                .orEmpty()
            return transcript.trim()
        }
    }
}
