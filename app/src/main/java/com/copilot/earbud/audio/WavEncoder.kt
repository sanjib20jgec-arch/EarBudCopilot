package com.copilot.earbud.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw 16-bit PCM mono 16kHz audio samples into a valid RIFF WAVE byte array in-memory.
 */
object WavEncoder {

    fun encodePcm16MonoToWav(pcmShorts: ShortArray, sampleRate: Int = 16000): ByteArray {
        val pcmByteSize = pcmShorts.size * 2
        val totalDataLen = pcmByteSize + 36
        val byteRate = sampleRate * 2 // 16-bit mono = 2 bytes per sample

        val output = ByteArrayOutputStream(pcmByteSize + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt sub-chunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat 1 = Linear PCM
        header.putShort(1) // NumChannels 1 = Mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // BlockAlign = NumChannels * BitsPerSample/8
        header.putShort(16) // BitsPerSample = 16

        // data sub-chunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmByteSize)

        output.write(header.array())

        val pcmBuffer = ByteBuffer.allocate(pcmByteSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmShorts) {
            pcmBuffer.putShort(sample)
        }
        output.write(pcmBuffer.array())

        return output.toByteArray()
    }
}
