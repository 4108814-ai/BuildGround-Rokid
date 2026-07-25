package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class MediaSyncProtocolTest {
    @Test
    fun `packets round trip through the framing`() {
        val output = ByteArrayOutputStream()
        val chunk = MediaSyncPacket(
            type = MediaSyncPacketType.FILE_CHUNK,
            seq = 7,
            payload = ByteArray(4096) { (it % 251).toByte() },
        )
        val end = MediaSyncPacket(
            type = MediaSyncPacketType.FILE_END,
            meta = """{"name":"img-1.jpg","sha256":"deadbeef"}""",
        )

        MediaSyncProtocol.write(output, chunk)
        MediaSyncProtocol.write(output, end)

        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals(chunk, MediaSyncProtocol.read(input))
        assertEquals(end, MediaSyncProtocol.read(input))
        assertNull(MediaSyncProtocol.read(input))
    }

    @Test
    fun `every packet type survives an id round trip`() {
        MediaSyncPacketType.entries.forEach { type ->
            assertEquals(type, MediaSyncPacketType.fromId(type.id))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown packet ids are rejected`() {
        MediaSyncPacketType.fromId(99)
    }

    @Test(expected = EOFException::class)
    fun `a truncated header is a hard error not a clean end`() {
        val output = ByteArrayOutputStream()
        MediaSyncProtocol.write(output, MediaSyncPacket(MediaSyncPacketType.BYE))

        MediaSyncProtocol.read(
            ByteArrayInputStream(output.toByteArray().copyOf(MediaSyncProtocol.HEADER_BYTES - 3)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `foreign framing is rejected by the magic`() {
        MediaSyncProtocol.read(ByteArrayInputStream(ByteArray(MediaSyncProtocol.HEADER_BYTES)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized payloads never reach the wire`() {
        MediaSyncProtocol.write(
            ByteArrayOutputStream(),
            MediaSyncPacket(
                type = MediaSyncPacketType.FILE_CHUNK,
                payload = ByteArray(MediaSyncProtocol.MAX_PAYLOAD_BYTES + 1),
            ),
        )
    }

    @Test
    fun `token comparison rejects wrong length and wrong content`() {
        assertTrue(MediaSyncProtocol.tokensMatch("0123456789abcdef", "0123456789abcdef"))
        assertFalse(MediaSyncProtocol.tokensMatch("0123456789abcdef", "0123456789abcde"))
        assertFalse(MediaSyncProtocol.tokensMatch("0123456789abcdef", "0123456789abcdee"))
        assertFalse(MediaSyncProtocol.tokensMatch("", "x"))
    }

    @Test
    fun `digest helper produces lowercase hex`() {
        val digest = MediaSyncProtocol.newDigest()
        digest.update("nexus".toByteArray())

        val hex = MediaSyncProtocol.hex(digest)

        assertEquals(64, hex.length)
        assertTrue(hex.matches(Regex("[0-9a-f]{64}")))
    }
}
