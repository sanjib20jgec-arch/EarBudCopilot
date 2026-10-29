package com.copilot.earbud.data.remote

/**
 * Decides whether a freshly transcribed microphone chunk is actually our own spoken answer leaking
 * back through the speaker into the microphone.
 *
 * Needed because the microphone is deliberately **not** muted while the answer is played: muting it
 * used to make the assistant deaf for the whole duration of a reply. Instead of deafening it, we
 * keep listening and drop the chunks that are recognisably the sentence we just said, so a question
 * asked *during* the reply is still caught while our own voice never triggers another answer.
 *
 * Pure Kotlin (no Android types) so it is covered by JVM unit tests.
 */
internal object SelfEchoDetector {

    /** Share of the heard words that must also exist in the spoken answer. */
    const val MIN_ECHO_OVERLAP = 0.6f

    /**
     * @param heard raw speech-to-text output of the microphone chunk
     * @param spoken the answer we just synthesised (null/blank when we have not spoken yet)
     */
    fun isLikelyEcho(heard: String, spoken: String?): Boolean {
        if (spoken.isNullOrBlank()) return false

        val spokenTokens = tokenize(spoken)
        val heardTokens = tokenize(heard)
        if (spokenTokens.isEmpty() || heardTokens.isEmpty()) return false

        val overlap = heardTokens.count { it in spokenTokens }
        return overlap.toFloat() / heardTokens.size >= MIN_ECHO_OVERLAP
    }

    /**
     * Lower-cases, strips punctuation/emoji and drops one-character tokens, which are almost always
     * transcription noise. Bengali/Devanagari letters are kept (`\p{L}` matches any script).
     */
    fun tokenize(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(' ')
            .filter { it.length > 1 }
            .toSet()
}
