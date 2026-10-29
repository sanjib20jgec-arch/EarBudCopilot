package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.SttProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speech-to-Text Repository supporting:
 * 1. Groq Whisper (`whisper-large-v3`) via multipart/form-data with Banglish prompt hint
 * 2. Deepgram Nova-2 (`nova-2`) via raw audio/wav binary POST
 */
class SttRepository {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeWav(
        wavBytes: ByteArray,
        provider: SttProvider,
        groqApiKey: String,
        deepgramApiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                SttProvider.GROQ_WHISPER -> transcribeWithGroqWhisper(wavBytes, groqApiKey)
                SttProvider.DEEPGRAM_NOVA2 -> transcribeWithDeepgram(wavBytes, deepgramApiKey)
            }
        }
    }

    private fun transcribeWithGroqWhisper(wavBytes: ByteArray, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Groq API key is missing. Enter it in the control panel." }

        val wavBody = wavBytes.toRequestBody("audio/wav".toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "ambient_chunk.wav", wavBody)
            .addFormDataPart("model", "whisper-large-v3")
            // Prompt guides Whisper to accurately transcribe code-mixed Bengali & English (Banglish)
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

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Groq STT HTTP ${response.code}: ${bodyStr.take(180)}")
            }
            val json = JSONObject(bodyStr)
            return json.optString("text", "").trim()
        }
    }

    private fun transcribeWithDeepgram(wavBytes: ByteArray, apiKey: String): String {
        require(apiKey.isNotBlank()) { "Deepgram API key is missing. Enter it in the control panel." }

        val requestBody = wavBytes.toRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true&detect_language=true")
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
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
