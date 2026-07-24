package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SpeechAudioEncodingTest {
    @Test
    fun wavHeaderIsMonoPcm16LittleEndian() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val wav = Pcm16Wav.encode(pcm, 16_000)

        assertEquals(48, wav.size)
        assertAscii("RIFF", wav, 0)
        assertIntLe(40, wav, 4)
        assertAscii("WAVE", wav, 8)
        assertAscii("fmt ", wav, 12)
        assertIntLe(16, wav, 16)
        assertShortLe(1, wav, 20)
        assertShortLe(1, wav, 22)
        assertIntLe(16_000, wav, 24)
        assertIntLe(32_000, wav, 28)
        assertShortLe(2, wav, 32)
        assertShortLe(16, wav, 34)
        assertAscii("data", wav, 36)
        assertIntLe(4, wav, 40)
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test
    fun pcmChunkerEmitsFullBoundariesAndFlushesRemainder() {
        val chunks = mutableListOf<ByteArray>()
        val chunker = PcmChunker(3_200, chunks::add)
        val first = ByteArray(2_000) { 1 }
        val second = ByteArray(2_000) { 2 }

        chunker.append(first, 0, first.size)
        chunker.append(second, 0, second.size)
        assertEquals(1, chunks.size)
        assertEquals(3_200, chunks.single().size)
        assertTrue(chunks.single().take(2_000).all { it == 1.toByte() })
        assertTrue(chunks.single().drop(2_000).all { it == 2.toByte() })

        chunker.flush()
        assertEquals(2, chunks.size)
        assertEquals(800, chunks[1].size)
        assertTrue(chunks[1].all { it == 2.toByte() })
    }

    @Test
    fun multipartFieldValuesAreUtf8() {
        val prompt = "廣東話語音。請用繁體中文轉寫。"
        val output = ByteArrayOutputStream()
        MultipartWriter(output).write("boundary") { boundary ->
            writeField(boundary, "prompt", prompt)
        }

        val body = output.toByteArray()
        val promptBytes = prompt.toByteArray(Charsets.UTF_8)
        assertTrue(body.containsSubsequence(promptBytes))
        assertTrue(String(body, Charsets.UTF_8).contains(prompt))
    }

    private fun assertAscii(expected: String, bytes: ByteArray, offset: Int) {
        assertEquals(expected, String(bytes, offset, expected.length, Charsets.US_ASCII))
    }

    private fun assertIntLe(expected: Int, bytes: ByteArray, offset: Int) {
        val actual = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        assertEquals(expected, actual)
    }

    private fun assertShortLe(expected: Int, bytes: ByteArray, offset: Int) {
        val actual = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
        assertEquals(expected, actual)
    }

    private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean {
        if (expected.isEmpty()) return true
        return indices.any { start ->
            start + expected.size <= size &&
                expected.indices.all { index -> this[start + index] == expected[index] }
        }
    }
}
