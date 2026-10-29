package com.copilot.earbud.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the "keep listening while speaking" safety net: our own TTS must never be re-answered,
 * while a genuine question asked during the reply must still get through.
 */
class SelfEchoDetectorTest {

    @Test
    fun `identical sentence is echo`() {
        val spoken = "Digha is about 185 kilometres from Kolkata."
        assertTrue(SelfEchoDetector.isLikelyEcho(spoken, spoken))
    }

    @Test
    fun `transcript with punctuation and casing differences is still echo`() {
        val spoken = "Digha is about 185 kilometres from Kolkata"
        val heard = "digha, is about 185 kilometres... from KOLKATA!"
        assertTrue(SelfEchoDetector.isLikelyEcho(heard, spoken))
    }

    @Test
    fun `partially heard own answer is echo`() {
        val spoken = "The distance between Kolkata and Digha is about 185 kilometres"
        val heard = "between Kolkata and Digha is about 185 kilometres"
        assertTrue(SelfEchoDetector.isLikelyEcho(heard, spoken))
    }

    @Test
    fun `bengali script answer echoing back is detected`() {
        val spoken = "দিঘা কলকাতা থেকে প্রায় ১৮৫ কিলোমিটার দূরে।"
        val heard = "দিঘা কলকাতা থেকে প্রায় ১৮৫ কিলোমিটার দূরে"
        assertTrue(SelfEchoDetector.isLikelyEcho(heard, spoken))
    }

    @Test
    fun `a new question asked while speaking is not echo`() {
        val spoken = "Digha is about 185 kilometres from Kolkata."
        val heard = "কালকে ইন্ডিয়ার ম্যাচ কার সাথে?"
        assertFalse(SelfEchoDetector.isLikelyEcho(heard, spoken))
    }

    @Test
    fun `english question about the same topic is not echo`() {
        val spoken = "Digha is about 185 kilometres from Kolkata."
        val heard = "How long does it take to drive there?"
        assertFalse(SelfEchoDetector.isLikelyEcho(heard, spoken))
    }

    @Test
    fun `nothing spoken yet means never echo`() {
        assertFalse(SelfEchoDetector.isLikelyEcho("Kolkata theke Digha koto dure?", null))
        assertFalse(SelfEchoDetector.isLikelyEcho("Kolkata theke Digha koto dure?", "   "))
    }

    @Test
    fun `blank transcript never triggers echo`() {
        assertFalse(SelfEchoDetector.isLikelyEcho("", "Some answer was spoken here."))
        assertFalse(SelfEchoDetector.isLikelyEcho("   ", "Some answer was spoken here."))
    }

    @Test
    fun `single character noise tokens are ignored when tokenizing`() {
        assertFalse(SelfEchoDetector.tokenize("a b c d").contains("a"))
        assertTrue(SelfEchoDetector.tokenize("Kolkata 185").containsAll(listOf("kolkata", "185")))
    }
}
