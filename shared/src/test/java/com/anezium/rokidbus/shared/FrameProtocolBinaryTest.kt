package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Byte-fidelity of the binary envelope path.
 *
 * Photo sync is the first feature to push binary from the glasses to the phone (the HUD image
 * channel only ever went phone -> glasses, and the camera's frozen images rode a TCP socket), so
 * the framing deserves an explicit round trip rather than trust: content must survive, not just
 * length.
 */
class FrameProtocolBinaryTest {
    private fun roundTrip(envelope: BusEnvelope): BusEnvelope {
        val out = ByteArrayOutputStream()
        FrameProtocol.write(out, envelope)
        return FrameProtocol.read(ByteArrayInputStream(out.toByteArray()))
            ?: error("frame did not decode")
    }

    private fun payload(size: Int, seed: Long): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return bytes
    }

    @Test
    fun `binary payloads survive byte for byte at every interesting size`() {
        // 1 byte, a chunk exactly, one under, one over, and a non-aligned middle size.
        listOf(
            1,
            2,
            255,
            MediaSyncTransferContract.CHUNK_BYTES - 1,
            MediaSyncTransferContract.CHUNK_BYTES,
            MediaSyncTransferContract.CHUNK_BYTES + 1,
            17_389,
        ).forEach { size ->
            val data = payload(size, seed = size.toLong())
            val decoded = roundTrip(
                BusEnvelope(
                    path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                    payload = MediaSyncTransferContract.chunkMeta("s", "img-1.jpg", 3, 96L),
                    binary = data,
                ),
            )

            assertEquals("size $size length", size, decoded.binary?.size)
            assertArrayEquals("size $size content", data, decoded.binary)
        }
    }

    @Test
    fun `header size does not bleed into the payload`() {
        // Vary the header length so any off-by-headerLen slice shows up as shifted content.
        listOf(1, 40, 200, 900).forEach { nameLength ->
            val name = "i".repeat(nameLength) + ".jpg"
            val data = payload(4_096, seed = nameLength.toLong())
            val decoded = roundTrip(
                BusEnvelope(
                    path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                    payload = MediaSyncTransferContract.chunkMeta("session", name, 7, 229_376L),
                    binary = data,
                ),
            )

            assertArrayEquals("header $nameLength", data, decoded.binary)
            assertEquals(name, MediaSyncTransferContract.name(decoded.payload))
            assertEquals(229_376L, MediaSyncTransferContract.offset(decoded.payload))
        }
    }

    @Test
    fun `a multi-byte header does not shift the payload`() {
        // Non-ASCII metadata makes the JSON's UTF-8 byte length differ from its char count, which
        // is exactly the class of bug that keeps lengths right and corrupts content.
        val data = payload(8_192, seed = 99L)
        val decoded = roundTrip(
            BusEnvelope(
                path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                payload = JSONObject().put("note", "café — vidéo 🎥").put("offset", 42),
                binary = data,
            ),
        )

        assertArrayEquals(data, decoded.binary)
        assertEquals(42L, MediaSyncTransferContract.offset(decoded.payload))
    }

    @Test
    fun `a stream of chunks decodes independently, with no bleed between frames`() {
        // The device cadence is ~41 ms per chunk; if the decoder ever handed out a slice of a
        // reused read buffer, a later frame would overwrite an earlier payload and only this
        // multi-frame read would catch it.
        val out = ByteArrayOutputStream()
        val sent = (0 until 12).map { index ->
            payload(MediaSyncTransferContract.CHUNK_BYTES, seed = index.toLong()).also { data ->
                FrameProtocol.write(
                    out,
                    BusEnvelope(
                        path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                        payload = MediaSyncTransferContract.chunkMeta(
                            "s",
                            "v.mp4",
                            index,
                            index.toLong() * MediaSyncTransferContract.CHUNK_BYTES,
                        ),
                        binary = data,
                    ),
                )
            }
        }

        val input = ByteArrayInputStream(out.toByteArray())
        val received = (0 until 12).map {
            FrameProtocol.read(input) ?: error("frame $it did not decode")
        }

        received.forEachIndexed { index, envelope ->
            assertEquals(
                index.toLong() * MediaSyncTransferContract.CHUNK_BYTES,
                MediaSyncTransferContract.offset(envelope.payload),
            )
            assertArrayEquals("frame $index", sent[index], envelope.binary)
        }
    }

    @Test
    fun `json envelopes still round trip alongside binary ones`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.write(
            out,
            BusEnvelope(
                path = BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
                payload = MediaSyncTransferContract.fileBegin("s", "a.jpg", 100L, 1L, 0L),
            ),
        )
        val data = payload(1_024, seed = 7L)
        FrameProtocol.write(
            out,
            BusEnvelope(
                path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                payload = MediaSyncTransferContract.chunkMeta("s", "a.jpg", 0, 0L),
                binary = data,
            ),
        )

        val input = ByteArrayInputStream(out.toByteArray())
        val begin = FrameProtocol.read(input)
        val chunk = FrameProtocol.read(input)

        assertNotNull(begin)
        assertEquals(null, begin?.binary)
        assertEquals(100L, begin?.payload?.optLong("size"))
        assertArrayEquals(data, chunk?.binary)
    }

    @Test
    fun `the whole-file digest matches a chunked send`() {
        // Mirrors the sender: hash the source once, ship it in chunks, hash what was received.
        val source = payload(MediaSyncTransferContract.CHUNK_BYTES * 3 + 511, seed = 4L)
        val sourceDigest = MediaSyncTransferContract.newDigest()
        sourceDigest.update(source)
        val expected = MediaSyncTransferContract.hex(sourceDigest)

        val out = ByteArrayOutputStream()
        var position = 0
        var seq = 0
        while (position < source.size) {
            val length = minOf(MediaSyncTransferContract.CHUNK_BYTES, source.size - position)
            FrameProtocol.write(
                out,
                BusEnvelope(
                    path = BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                    payload = MediaSyncTransferContract.chunkMeta("s", "v.mp4", seq, position.toLong()),
                    binary = source.copyOfRange(position, position + length),
                ),
            )
            position += length
            seq += 1
        }

        val input = ByteArrayInputStream(out.toByteArray())
        val received = ByteArrayOutputStream()
        repeat(seq) {
            val envelope = FrameProtocol.read(input) ?: error("missing frame")
            received.write(envelope.binary!!)
        }
        val receivedDigest = MediaSyncTransferContract.newDigest()
        receivedDigest.update(received.toByteArray())

        assertArrayEquals(source, received.toByteArray())
        assertEquals(expected, MediaSyncTransferContract.hex(receivedDigest))
    }
}
