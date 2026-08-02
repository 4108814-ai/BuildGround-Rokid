package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmEncoderTest {
    @Test
    fun assemblesPcm16LittleEndianWav() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = WavPcmEncoder.encode(
            pcm = pcm,
            format = NexusAudioFormat(
                sampleRate = 16_000,
                channels = 1,
                encoding = "pcm16le",
            ),
        )
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(40, header.getInt(4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun acceptsHubAndSdkPcm16EncodingNames() {
        val pcm = byteArrayOf(0, 0)
        listOf("pcm16le", "s16le", "PCM_16_LE").forEach { encoding ->
            val wav = WavPcmEncoder.encode(
                pcm,
                NexusAudioFormat(16_000, 1, encoding),
            )
            assertEquals(46, wav.size)
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)
}
