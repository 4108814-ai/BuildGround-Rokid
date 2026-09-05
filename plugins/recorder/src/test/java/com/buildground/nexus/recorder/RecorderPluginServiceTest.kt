package com.buildground.nexus.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderPluginServiceTest {
    @Test
    fun `wav header describes pcm16 mono payload`() {
        val header = RecorderPluginService.wavHeader(
            dataBytes = 32_000L,
            sampleRate = 16_000,
            channels = 1,
        )
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(header.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("data", String(header.copyOfRange(36, 40), Charsets.US_ASCII))
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32_000, bytes.getInt(40))
        assertEquals(16_000, bytes.getInt(24))
        assertEquals(32_000, bytes.getInt(28))
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
    }

    @Test
    fun `wav header keeps unsigned 32 bit sizes as raw little endian`() {
        val header = RecorderPluginService.wavHeader(
            dataBytes = 0xffff_ff00L,
            sampleRate = 16_000,
            channels = 1,
        )
        assertTrue(header.size == 44)
        assertEquals(0xffff_ff00L, littleEndianUnsignedInt(header, 40))
    }

    private fun littleEndianUnsignedInt(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }
}
