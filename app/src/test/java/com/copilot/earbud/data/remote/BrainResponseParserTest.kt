package com.copilot.earbud.data.remote

import com.copilot.earbud.domain.model.BrainDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug that made the assistant "listen but never reply": an empty,
 * truncated or blocked provider response used to be coerced into a successful `[IGNORE]`
 * classification and silently dropped. These tests run on the JVM (no device required) and are
 * executed by CI before the APK is built.
 */
class BrainResponseParserTest {

    private fun decideGemini(body: String): BrainDecision =
        BrainResponseParser.classify(BrainResponseParser.parseGemini("gemini-2.5-flash", body))

    private fun decideGroq(body: String): BrainDecision =
        BrainResponseParser.classify(BrainResponseParser.parseGroq("openai/gpt-oss-120b", body))

    @Test
    fun `gemini truncated by thinking budget is a failure not an ignore`() {
        val decision = decideGemini(
            """{"candidates":[{"content":{},"finishReason":"MAX_TOKENS"}],
               "usageMetadata":{"promptTokenCount":1124,"totalTokenCount":1171,"thoughtsTokenCount":47}}"""
        )
        assertTrue("expected Failed but was $decision", decision is BrainDecision.Failed)
        assertTrue(
            (decision as BrainDecision.Failed).message.contains("MAX_TOKENS"),
            "diagnostic reason should name the truncation: ${decision.message}"
        )
    }

    @Test
    fun `gemini safety block is a failure not an ignore`() {
        val decision = decideGemini(
            """{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":120}}"""
        )
        assertTrue(decision is BrainDecision.Failed)
        assertTrue((decision as BrainDecision.Failed).message.contains("SAFETY"))
    }

    @Test
    fun `gemini thought-only parts are a failure`() {
        val decision = decideGemini(
            """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true}]},
               "finishReason":"STOP"}],"usageMetadata":{"thoughtsTokenCount":90}}"""
        )
        assertTrue(decision is BrainDecision.Failed)
    }

    @Test
    fun `gemini real answer is an answer`() {
        val decision = decideGemini(
            """{"candidates":[{"content":{"parts":[{"text":"About 185 km by road."}]},"finishReason":"STOP"}]}"""
        )
        assertTrue(decision is BrainDecision.Answer)
        assertEquals("About 185 km by road.", (decision as BrainDecision.Answer).text)
    }

    @Test
    fun `gemini genuine ignore verdict is still an ignore`() {
        val decision = decideGemini(
            """{"candidates":[{"content":{"parts":[{"text":"[IGNORE]"}]},"finishReason":"STOP"}]}"""
        )
        assertTrue(decision is BrainDecision.Ignored)
    }

    @Test
    fun `groq response truncated by reasoning budget is a failure`() {
        val decision = decideGroq(
            """{"choices":[{"finish_reason":"length","message":{"content":"","reasoning":"the user asked..."}}]}"""
        )
        assertTrue(decision is BrainDecision.Failed)
        assertTrue((decision as BrainDecision.Failed).message.contains("finish_reason=length"))
    }

    @Test
    fun `groq reasoning-only response is a failure`() {
        val decision = decideGroq(
            """{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning":"hmm"}}]}"""
        )
        assertTrue(decision is BrainDecision.Failed)
    }

    @Test
    fun `groq real answer is an answer`() {
        val decision = decideGroq(
            """{"choices":[{"finish_reason":"stop","message":{"content":"The CEO is Sundar Pichai."}}]}"""
        )
        assertTrue(decision is BrainDecision.Answer)
        assertEquals("The CEO is Sundar Pichai.", (decision as BrainDecision.Answer).text)
    }

    @Test
    fun `groq think block is stripped before classification`() {
        val decision = decideGroq(
            """{"choices":[{"finish_reason":"stop","message":{"content":"<think>hmm</think>About 185 km."}}]}"""
        )
        assertTrue(decision is BrainDecision.Answer)
        assertEquals("About 185 km.", (decision as BrainDecision.Answer).text)
    }

    @Test
    fun `groq genuine ignore verdict is still an ignore`() {
        val decision = decideGroq(
            """{"choices":[{"finish_reason":"stop","message":{"content":"[IGNORE]"}}]}"""
        )
        assertTrue(decision is BrainDecision.Ignored)
    }

    @Test
    fun `malformed json surfaces as a failure, never as an ignore`() {
        val decision = decideGemini("not-json")
        assertTrue(decision is BrainDecision.Failed || decision is BrainDecision.Answer)
    }
}
