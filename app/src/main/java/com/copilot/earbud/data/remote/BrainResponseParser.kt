package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.BrainDecision
import org.json.JSONObject

/**
 * Raw outcome of a single provider call, before classification.
 *
 * Keeping these cases apart is what allows the pipeline to tell "the model said this is not a
 * question" ([Text] containing `[IGNORE]`) apart from "the model produced no text at all"
 * ([NonAnswer]) and "the request failed" ([HttpError]).
 */
internal sealed class ProviderCall {

    /** The provider returned real, non-blank text. */
    data class Text(val model: String, val text: String) : ProviderCall()

    /** HTTP 200, but there is no answer text - truncated, safety-blocked or empty candidate. */
    data class NonAnswer(val model: String, val reason: String) : ProviderCall()

    /** Non-2xx response or a transport failure. */
    data class HttpError(val model: String, val message: String) : ProviderCall()
}

/**
 * Pure response parsing/classification: no network, no Android APIs, so it is unit-testable on the
 * JVM. This is the code that previously reported an empty or truncated provider response as a valid
 * `[IGNORE]`, which made "the assistant never answers" indistinguishable from correct filtering.
 */
internal object BrainResponseParser {

    const val IGNORE_TOKEN = "[IGNORE]"

    /**
     * Head-room for one short sentence *plus* any hidden reasoning tokens. Gemini 2.5/3 Flash count
     * thinking tokens against `maxOutputTokens`; a tight budget made them return an empty candidate
     * (finishReason=MAX_TOKENS) which used to be misread as "[IGNORE]". Thinking is normally
     * disabled now, so 512 still leaves ~30x the room a one-sentence answer needs while halving the
     * worst-case generation time compared to 1024.
     */
    const val GEMINI_MAX_OUTPUT_TOKENS = 512

    /** Same idea for Groq: enough for a one-line answer plus the reasoning of reasoning models. */
    const val GROQ_MAX_TOKENS = 512

    /** Maps a provider call onto a decision; a blank/blocked/truncated response is a FAILURE. */
    fun classify(call: ProviderCall): BrainDecision = when (call) {
        is ProviderCall.Text -> {
            val cleaned = stripThinking(call.text).trim()
            when {
                cleaned.isEmpty() ->
                    BrainDecision.Failed("${call.model}: response contained no answer text")
                cleaned.contains(IGNORE_TOKEN, ignoreCase = true) ->
                    BrainDecision.Ignored("${call.model} classified the transcript as non-factual / conversational")
                else ->
                    BrainDecision.Answer(cleaned, call.model)
            }
        }

        is ProviderCall.NonAnswer -> BrainDecision.Failed("${call.model}: ${call.reason}")
        is ProviderCall.HttpError -> BrainDecision.Failed(call.message)
    }

    fun stripThinking(raw: String): String =
        raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

    /** Parses a `generateContent` response body. */
    fun parseGemini(model: String, bodyStr: String): ProviderCall {
        val json = JSONObject(bodyStr)

        val blockReason = json.optJSONObject("promptFeedback")
            ?.optString("blockReason")
            .orEmpty()
        val usage = json.optJSONObject("usageMetadata")
        val thoughts = usage?.optInt("thoughtsTokenCount", 0) ?: 0

        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val finishReason = candidate?.optString("finishReason").orEmpty()
        val text = candidate
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                buildString {
                    for (index in 0 until parts.length()) {
                        val part = parts.optJSONObject(index) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        append(part.optString("text").orEmpty())
                    }
                }
            }
            .orEmpty()
            .trim()

        if (text.isNotEmpty()) return ProviderCall.Text(model, text)

        val reason = when {
            blockReason.isNotBlank() ->
                "request blocked by the provider (promptFeedback.blockReason=$blockReason)"
            finishReason == "MAX_TOKENS" ->
                "response truncated before any answer (finishReason=MAX_TOKENS, " +
                    "thoughtsTokenCount=$thoughts, maxOutputTokens=$GEMINI_MAX_OUTPUT_TOKENS)"
            finishReason == "SAFETY" || finishReason == "RECITATION" || finishReason == "PROHIBITED_CONTENT" ->
                "response withheld by the provider (finishReason=$finishReason)"
            finishReason.isNotBlank() ->
                "provider returned no answer text (finishReason=$finishReason, thoughtsTokenCount=$thoughts)"
            else ->
                "provider returned an empty response (thoughtsTokenCount=$thoughts)"
        }
        return ProviderCall.NonAnswer(model, reason)
    }

    /** Parses an OpenAI-compatible `chat/completions` response body (Groq). */
    fun parseGroq(model: String, bodyStr: String): ProviderCall {
        val json = JSONObject(bodyStr)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val finishReason = choice?.optString("finish_reason").orEmpty()
        val message = choice?.optJSONObject("message")
        val text = stripThinking(message?.optString("content").orEmpty())
        val reasoningChars = message?.optString("reasoning").orEmpty().length

        if (text.isNotEmpty()) return ProviderCall.Text(model, text)

        val reason = when {
            finishReason == "length" ->
                "response truncated before any answer (finish_reason=length, " +
                    "max_tokens=$GROQ_MAX_TOKENS, reasoningChars=$reasoningChars)"
            finishReason.isNotBlank() ->
                "provider returned no answer text (finish_reason=$finishReason, reasoningChars=$reasoningChars)"
            reasoningChars > 0 ->
                "provider returned reasoning only, no answer text (reasoningChars=$reasoningChars)"
            else ->
                "provider returned an empty response"
        }
        return ProviderCall.NonAnswer(model, reason)
    }
}
