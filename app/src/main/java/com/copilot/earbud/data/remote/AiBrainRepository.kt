package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.BrainDecision
import com.copilot.earbud.domain.model.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Evaluates transcribed ambient speech using the strict Banglish Ambient Copilot System Prompt.
 * Automatically discovers and falls back across active Gemini (e.g. gemini-3.0-flash, gemini-2.5-flash)
 * and Groq (e.g. openai/gpt-oss-120b, meta-llama/llama-4-scout-17b-16e-instruct) models.
 *
 * IMPORTANT: this class never turns a failed/empty/blocked provider response into an `[IGNORE]`
 * classification. Only text that the model actually returned is classified; everything else is
 * reported as [BrainDecision.Failed] with a diagnostic reason, so the pipeline can distinguish
 * "not a question" from "we never got an answer".
 */
class AiBrainRepository {

    companion object {
        // Ordered for the FIRST call of a session (the winner is then cached): quality first, but
        // still Groq-fast. Reasoning models come last because they add seconds of latency.
        private val GROQ_FALLBACK_MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "openai/gpt-oss-20b",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "openai/gpt-oss-120b"
        )

        /**
         * Models that rejected the *model name* itself (HTTP 400/404 "not found / not supported").
         * They were re-probed on every single utterance, which quietly added a full round-trip of
         * latency before the working model was reached. Remember them for the process lifetime.
         */
        private val unusableModels: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private val GEMINI_FALLBACK_MODELS = listOf(
            "gemini-3.0-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest"
        )

        val SYSTEM_PROMPT = """
You are a discreet earbud assistant listening to a real conversation in code-mixed Bengali and English (Banglish).

Rules:
1. If the transcript asks a factual, informational or objective question, answer it.
2. If it is small talk, personal, social or rhetorical (e.g. "তুই কেমন আছিস?", "চা খাবি?", "আজকে গরম তাই না?"), reply with exactly and only: [IGNORE]
3. The transcript may be a partially heard or ungrammatical fragment. If the question is still recognisable, answer it anyway.
4. Answer in ONE short sentence of at most 15 words, using the same language mix and script as the question: Bengali script for Bengali, Banglish in Latin letters for Banglish, English for English.
5. State the fact directly. No preamble ("Sure", "Here is"), no explanation, no disclaimer, no second sentence.

Example good answer: "দিঘা কলকাতা থেকে প্রায় ১৮৫ কিলোমিটার দূরে।"
Example bad answer: "Sure! The distance from Kolkata to Digha is about 185 km by road, which takes around four hours."
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
    ): Result<BrainDecision> = withContext(Dispatchers.IO) {
        try {
            val preferred = askProvider(provider, transcript, geminiApiKey, groqApiKey)
            if (preferred !is BrainDecision.Failed) {
                return@withContext Result.success(preferred)
            }

            // Cross-provider fallback: a single working key (e.g. only Groq) should still answer.
            val alternative = alternativeProvider(provider, geminiApiKey, groqApiKey)
                ?: return@withContext Result.success(preferred)

            val second = askProvider(alternative, transcript, geminiApiKey, groqApiKey)
            if (second !is BrainDecision.Failed) {
                return@withContext Result.success(second)
            }

            Result.success(
                BrainDecision.Failed(
                    "${preferred.message} | fallback ${alternative.name}: ${second.message}"
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun askProvider(
        provider: LlmProvider,
        transcript: String,
        geminiApiKey: String,
        groqApiKey: String
    ): BrainDecision = try {
        val call = when (provider) {
            LlmProvider.GEMINI_FLASH -> queryGeminiWithFallback(transcript, geminiApiKey)
            LlmProvider.GROQ_LLAMA -> queryGroqWithFallback(transcript, groqApiKey)
        }
        BrainResponseParser.classify(call)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        BrainDecision.Failed("${provider.name}: ${t.message ?: t.toString()}")
    }

    private fun alternativeProvider(
        provider: LlmProvider,
        geminiApiKey: String,
        groqApiKey: String
    ): LlmProvider? = when (provider) {
        LlmProvider.GEMINI_FLASH -> if (groqApiKey.isNotBlank()) LlmProvider.GROQ_LLAMA else null
        LlmProvider.GROQ_LLAMA -> if (geminiApiKey.isNotBlank()) LlmProvider.GEMINI_FLASH else null
    }

    /** Cheap credential/connectivity probe used by the in-app diagnostics button. */
    suspend fun pingProviders(geminiApiKey: String, groqApiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()

            if (geminiApiKey.isBlank()) {
                lines += "Gemini: no key stored."
            } else {
                val models = fetchAvailableGeminiModels(geminiApiKey)
                lines += if (models.isEmpty()) {
                    "Gemini: key rejected or unreachable (no models listed)."
                } else {
                    "Gemini: OK - ${models.size} models available (first: ${models.first()})."
                }
            }

            if (groqApiKey.isBlank()) {
                lines += "Groq: no key stored."
            } else {
                val models = fetchAvailableGroqModels(groqApiKey)
                lines += if (models.isEmpty()) {
                    "Groq: key rejected or unreachable (no models listed)."
                } else {
                    "Groq: OK - ${models.size} models available (first: ${models.first()})."
                }
            }

            lines
        }

    /**
     * One retry for throttling / transient server errors. A single 429 used to silently end the
     * utterance (the assistant simply stayed quiet).
     */
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

    /**
     * Maps a provider call onto a decision. A blank / blocked / truncated response is a FAILURE and
     * is reported as such — it must never be silently downgraded to "not a question".
     */

    // ---------------------------------------------------------------------------------------------
    // Gemini
    // ---------------------------------------------------------------------------------------------

    private fun queryGeminiWithFallback(transcript: String, apiKey: String): ProviderCall {
        require(apiKey.isNotBlank()) { "Gemini API key is missing. Enter it in API Keys & Pipeline Engine." }

        val candidates = LinkedHashSet<String>()
        resolvedGeminiModel?.let { if (it !in unusableModels) candidates.add(it) }
        candidates.addAll(GEMINI_FALLBACK_MODELS)

        var lastError = ""
        for (modelId in candidates) {
            if (modelId in unusableModels) continue
            when (val call = tryGeminiGenerateContent(transcript, apiKey, modelId)) {
                is ProviderCall.Text -> {
                    resolvedGeminiModel = modelId
                    return call
                }
                // The request itself worked; the model just produced no text. Report it instead of
                // masking it as "[IGNORE]", and do not burn more latency on other models for it.
                is ProviderCall.NonAnswer -> return call
                is ProviderCall.HttpError -> {
                    lastError = call.message
                    // If the error explicitly names a replacement model, try it immediately.
                    val suggested = Regex("models/(gemini-[a-zA-Z0-9._-]+)")
                        .find(call.message)
                        ?.groupValues
                        ?.getOrNull(1)
                    if (!suggested.isNullOrBlank() && suggested !in candidates) {
                        when (val retry = tryGeminiGenerateContent(transcript, apiKey, suggested)) {
                            is ProviderCall.Text -> {
                                resolvedGeminiModel = suggested
                                return retry
                            }
                            is ProviderCall.NonAnswer -> return retry
                            is ProviderCall.HttpError -> lastError = retry.message
                        }
                    }
                }
            }
        }

        // Dynamic fallback: query /v1beta/models to find any active model supporting generateContent.
        for (modelId in fetchAvailableGeminiModels(apiKey)) {
            if (modelId in candidates || modelId in unusableModels) continue
            when (val call = tryGeminiGenerateContent(transcript, apiKey, modelId)) {
                is ProviderCall.Text -> {
                    resolvedGeminiModel = modelId
                    return call
                }
                is ProviderCall.NonAnswer -> return call
                is ProviderCall.HttpError -> lastError = call.message
            }
        }

        return ProviderCall.HttpError("gemini", lastError.ifBlank { "No Gemini model responded." })
    }

    private fun tryGeminiGenerateContent(
        transcript: String,
        apiKey: String,
        modelId: String
    ): ProviderCall {
        val withThinkingControl = supportsThinkingControl(modelId)
        val first = callGemini(transcript, apiKey, modelId, withThinkingControl)
        // Some older models reject the unknown `thinkingConfig` field with HTTP 400. Retry once
        // without it rather than failing the whole utterance.
        if (withThinkingControl &&
            first is ProviderCall.HttpError &&
            first.message.contains("HTTP 400") &&
            first.message.contains("thinking", ignoreCase = true)
        ) {
            return callGemini(transcript, apiKey, modelId, false)
        }
        return first
    }

    private fun callGemini(
        transcript: String,
        apiKey: String,
        modelId: String,
        withThinkingControl: Boolean
    ): ProviderCall {
        val cleanModel = modelId.removePrefix("models/")

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
                put("maxOutputTokens", BrainResponseParser.GEMINI_MAX_OUTPUT_TOKENS)
                if (withThinkingControl) {
                    // Thinking is on by default for 2.5/3 Flash and is billed against maxOutputTokens.
                    // Disabling it keeps a one-line answer from being truncated away entirely.
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            executeWithRetry(request).use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (isUnusableModelResponse(response.code, bodyStr)) {
                        unusableModels.add(cleanModel.removePrefix("models/"))
                    }
                    return ProviderCall.HttpError(
                        cleanModel,
                        "Gemini ($cleanModel) HTTP ${response.code}: ${bodyStr.take(200)}"
                    )
                }
                BrainResponseParser.parseGemini(cleanModel, bodyStr)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ProviderCall.HttpError(cleanModel, "Gemini ($cleanModel) request failed: ${t.message}")
        }
    }


    /**
     * Whether it is safe/meaningful to send `thinkingConfig` for this model. Older models reject the
     * field; [tryGeminiGenerateContent] additionally retries without it on HTTP 400.
     */
    private fun supportsThinkingControl(modelId: String): Boolean {
        val id = modelId.removePrefix("models/")
        return id.contains("2.5") ||
            id.contains("gemini-3") ||
            id.contains("gemini-4") ||
            id.contains("flash-latest") ||
            id.contains("pro-latest")
    }

    private fun fetchAvailableGeminiModels(apiKey: String): List<String> {
        return runCatching {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .addHeader("x-goog-api-key", apiKey)
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

    // ---------------------------------------------------------------------------------------------
    // Groq
    // ---------------------------------------------------------------------------------------------

    private fun queryGroqWithFallback(transcript: String, apiKey: String): ProviderCall {
        require(apiKey.isNotBlank()) { "Groq API key is missing. Enter it in API Keys & Pipeline Engine." }

        val candidates = LinkedHashSet<String>()
        resolvedGroqModel?.let { if (it !in unusableModels) candidates.add(it) }
        candidates.addAll(GROQ_FALLBACK_MODELS)

        var lastError = ""
        for (modelId in candidates) {
            if (modelId in unusableModels) continue
            when (val call = tryGroqChatCompletion(transcript, apiKey, modelId)) {
                is ProviderCall.Text -> {
                    resolvedGroqModel = modelId
                    return call
                }
                is ProviderCall.NonAnswer -> return call
                is ProviderCall.HttpError -> lastError = call.message
            }
        }

        for (modelId in fetchAvailableGroqModels(apiKey)) {
            if (modelId in candidates || modelId in unusableModels) continue
            when (val call = tryGroqChatCompletion(transcript, apiKey, modelId)) {
                is ProviderCall.Text -> {
                    resolvedGroqModel = modelId
                    return call
                }
                is ProviderCall.NonAnswer -> return call
                is ProviderCall.HttpError -> lastError = call.message
            }
        }

        return ProviderCall.HttpError("groq", lastError.ifBlank { "No Groq model responded." })
    }

    private fun tryGroqChatCompletion(
        transcript: String,
        apiKey: String,
        modelId: String
    ): ProviderCall {
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
            put("max_tokens", BrainResponseParser.GROQ_MAX_TOKENS)
            if (isReasoningModel(modelId)) {
                // Reasoning models spend part of the budget on hidden reasoning; keep it low so a
                // one-line answer is not starved (that previously surfaced as an empty "[IGNORE]").
                put("reasoning_effort", "low")
            }
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            executeWithRetry(request).use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (isUnusableModelResponse(response.code, bodyStr)) {
                        unusableModels.add(modelId)
                    }
                    return ProviderCall.HttpError(
                        modelId,
                        "Groq ($modelId) HTTP ${response.code}: ${bodyStr.take(200)}"
                    )
                }
                BrainResponseParser.parseGroq(modelId, bodyStr)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ProviderCall.HttpError(modelId, "Groq ($modelId) request failed: ${t.message}")
        }
    }

    /**
     * True only when the provider rejected the model itself, not the request content: a bad
     * `thinkingConfig` (also HTTP 400) must NOT blacklist an otherwise healthy model.
     */
    private fun isUnusableModelResponse(code: Int, body: String): Boolean {
        if (code != 400 && code != 404) return false
        val lower = body.lowercase()
        return lower.contains("not found") ||
            lower.contains("not supported") ||
            lower.contains("does not exist") ||
            lower.contains("no longer available") ||
            lower.contains("decommissioned") ||
            lower.contains("is not a valid model")
    }

    private fun isReasoningModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("gpt-oss") ||
            id.contains("qwen3") ||
            id.contains("deepseek") ||
            id.contains("reasoning")
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
