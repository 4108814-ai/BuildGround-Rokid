package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.mediasync.MediaSyncGalleryTransfer
import com.anezium.rokidbus.phone.mediasync.MediaSyncGalleryWriter
import com.anezium.rokidbus.phone.mediasync.MediaSyncStagingStore
import com.anezium.rokidbus.phone.mediasync.MediaSyncTransferReceiver
import com.anezium.rokidbus.phone.mediasync.SyncLedger
import com.anezium.rokidbus.phone.mediasync.SyncLedgerStorage
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncItem
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Random

class MediaSyncTransferReceiverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val session = "s1"
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var finished: MediaSyncRun? = null

    private class MemoryLedgerStorage : SyncLedgerStorage {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private class FakeGallery : MediaSyncGalleryWriter {
        val published = LinkedHashMap<String, ByteArray>()
        var existing: Pair<String, Long>? = null

        override fun alreadyPublished(name: String, sizeBytes: Long): Boolean =
            existing == (name to sizeBytes)

        override fun open(name: String): MediaSyncGalleryTransfer =
            object : MediaSyncGalleryTransfer {
                private val buffer = ByteArrayOutputStream()
                override fun append(buffer2: ByteArray, length: Int) = buffer.write(buffer2, 0, length)
                override fun publish(expectedSha256: String, capturedAtMillis: Long): Boolean {
                    published[name] = buffer.toByteArray()
                    return true
                }
                override fun discard() = Unit
                override fun close() = Unit
            }
    }

    private val ledger = SyncLedger(MemoryLedgerStorage())
    private val gallery = FakeGallery()
    private val staging by lazy { MediaSyncStagingStore(temporaryFolder.newFolder("staging")) }

    private fun receiver(deleteAfterSync: Boolean = false) = MediaSyncTransferReceiver(
        sessionId = session,
        ledger = ledger,
        gallery = gallery,
        staging = staging,
        deleteAfterSync = deleteAfterSync,
        clock = { 1_000L },
        logger = {},
        send = { path, payload -> sent += path to payload; true },
        onProgress = {},
        onDeletionOutcome = {},
        onFinished = { finished = it },
    )

    private fun bytes(size: Int, seed: Long): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun sha(data: ByteArray): String {
        val digest = MediaSyncTransferContract.newDigest()
        digest.update(data)
        return MediaSyncTransferContract.hex(digest)
    }

    private fun MediaSyncTransferReceiver.deliverCatalog(items: List<MediaSyncItem>) = onEnvelope(
        BusPaths.MEDIA_SYNC_XFER_CATALOG,
        MediaSyncCatalogContract.encode(items, false)
            .put("version", MediaSyncTransferContract.VERSION)
            .put("sessionId", session),
        null,
    )

    private fun MediaSyncTransferReceiver.deliverFile(
        item: MediaSyncItem,
        data: ByteArray,
        chunk: Int = MediaSyncTransferContract.CHUNK_BYTES,
    ) {
        onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(session, item.name, item.sizeBytes, 0L, 0L),
            null,
        )
        var offset = 0
        var seq = 0
        while (offset < data.size) {
            val length = minOf(chunk, data.size - offset)
            onEnvelope(
                BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                MediaSyncTransferContract.chunkMeta(session, item.name, seq, offset.toLong()),
                data.copyOfRange(offset, offset + length),
            )
            offset += length
            seq += 1
        }
        onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_END,
            MediaSyncTransferContract.fileEnd(session, item.name, sha(data)),
            null,
        )
    }

    @Test
    fun `a chunked file is published byte for byte`() {
        val data = bytes(MediaSyncTransferContract.CHUNK_BYTES * 2 + 777, seed = 1L)
        val item = MediaSyncItem("img-20260710-175956-a0-N1-2.jpg", data.size.toLong(), 5L)
        val receiver = receiver()

        receiver.deliverCatalog(listOf(item))
        receiver.deliverFile(item, data)

        assertArrayEquals(data, gallery.published[item.name])
        assertTrue(ledger.contains(item))
        assertEquals(MediaSyncResult.COMPLETED, finished?.result)
        assertEquals(1, finished?.filesSynced)
    }

    @Test
    fun `chunks delivered out of order are refused, not staged at the wrong place`() {
        // This is the shape of the bug that shipped: a thread pool reordered the stream, every
        // chunk still landed exactly once, so the length was right and only the content was wrong.
        val data = bytes(MediaSyncTransferContract.CHUNK_BYTES * 2, seed = 2L)
        val item = MediaSyncItem("img-a.jpg", data.size.toLong(), 5L)
        val receiver = receiver()
        receiver.deliverCatalog(listOf(item))
        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(session, item.name, item.sizeBytes, 0L, 0L),
            null,
        )

        // Second chunk first: it must be dropped rather than appended at offset 0.
        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            MediaSyncTransferContract.chunkMeta(
                session,
                item.name,
                1,
                MediaSyncTransferContract.CHUNK_BYTES.toLong(),
            ),
            data.copyOfRange(MediaSyncTransferContract.CHUNK_BYTES, data.size),
        )

        assertEquals(0L, staging.receivedBytes(item.name))
    }

    @Test
    fun `a corrupted transfer is never published and never enters the ledger`() {
        val data = bytes(4_096, seed = 3L)
        val item = MediaSyncItem("img-b.jpg", data.size.toLong(), 5L)
        val receiver = receiver()
        receiver.deliverCatalog(listOf(item))

        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(session, item.name, item.sizeBytes, 0L, 0L),
            null,
        )
        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            MediaSyncTransferContract.chunkMeta(session, item.name, 0, 0L),
            data,
        )
        // Right length, wrong content: exactly what the device reported.
        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_END,
            MediaSyncTransferContract.fileEnd(session, item.name, sha(bytes(4_096, seed = 99L))),
            null,
        )

        assertNull(gallery.published[item.name])
        assertTrue(ledger.pending(listOf(item)).isNotEmpty())
        assertEquals(0L, staging.receivedBytes(item.name))
        assertEquals(MediaSyncResult.FAILED, finished?.result)
    }

    @Test
    fun `a session gives up after three consecutive checksum failures`() {
        val items = (1..5).map { MediaSyncItem("img-$it.jpg", 512L, 5L) }
        val receiver = receiver()
        receiver.deliverCatalog(items)

        items.forEach { item ->
            if (finished != null) return@forEach
            receiver.onEnvelope(
                BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
                MediaSyncTransferContract.fileBegin(session, item.name, item.sizeBytes, 0L, 0L),
                null,
            )
            receiver.onEnvelope(
                BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                MediaSyncTransferContract.chunkMeta(session, item.name, 0, 0L),
                bytes(512, seed = 7L),
            )
            receiver.onEnvelope(
                BusPaths.MEDIA_SYNC_XFER_FILE_END,
                MediaSyncTransferContract.fileEnd(session, item.name, sha(bytes(512, seed = 8L))),
                null,
            )
        }

        assertEquals(MediaSyncResult.FAILED, finished?.result)
        assertEquals("Transfers keep arriving corrupted", finished?.message)
        assertNotNull(sent.firstOrNull { it.first == BusPaths.MEDIA_SYNC_XFER_ABORT })
    }

    @Test
    fun `an interrupted file resumes from the offset it reached`() {
        val data = bytes(MediaSyncTransferContract.CHUNK_BYTES * 2, seed = 4L)
        val item = MediaSyncItem("vid-a.mp4", data.size.toLong(), 5L)
        val first = receiver()
        first.deliverCatalog(listOf(item))
        first.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(session, item.name, item.sizeBytes, 0L, 0L),
            null,
        )
        first.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            MediaSyncTransferContract.chunkMeta(session, item.name, 0, 0L),
            data.copyOfRange(0, MediaSyncTransferContract.CHUNK_BYTES),
        )
        first.onLinkLost()

        assertEquals(MediaSyncTransferContract.CHUNK_BYTES.toLong(), staging.receivedBytes(item.name))

        // A second session must ask for the rest, not the whole file.
        sent.clear()
        finished = null
        val second = receiver()
        second.deliverCatalog(listOf(item))

        val request = sent.last { it.first == BusPaths.MEDIA_SYNC_XFER_FILE_REQUEST }.second
        assertEquals(MediaSyncTransferContract.CHUNK_BYTES.toLong(), MediaSyncTransferContract.offset(request))

        second.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(
                session,
                item.name,
                item.sizeBytes,
                0L,
                MediaSyncTransferContract.CHUNK_BYTES.toLong(),
            ),
            null,
        )
        second.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            MediaSyncTransferContract.chunkMeta(
                session,
                item.name,
                1,
                MediaSyncTransferContract.CHUNK_BYTES.toLong(),
            ),
            data.copyOfRange(MediaSyncTransferContract.CHUNK_BYTES, data.size),
        )
        second.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_END,
            MediaSyncTransferContract.fileEnd(session, item.name, sha(data)),
            null,
        )

        // The whole file, reassembled across two sessions, still hashes correctly.
        assertArrayEquals(data, gallery.published[item.name])
        assertEquals(MediaSyncResult.COMPLETED, finished?.result)
    }

    @Test
    fun `an empty pending set ends the session as up to date`() {
        val item = MediaSyncItem("img-c.jpg", 10L, 5L)
        ledger.record(item, 1L)
        val receiver = receiver()

        receiver.deliverCatalog(listOf(item))

        assertEquals(MediaSyncResult.UP_TO_DATE, finished?.result)
        assertNotNull(sent.firstOrNull { it.first == BusPaths.MEDIA_SYNC_XFER_BYE })
    }

    @Test
    fun `a capture Hi Rokid already imported is adopted, not fetched again`() {
        val item = MediaSyncItem("img-d.jpg", 64L, 5L)
        gallery.existing = item.name to item.sizeBytes
        val receiver = receiver()

        receiver.deliverCatalog(listOf(item))

        assertTrue(ledger.contains(item))
        assertNull(sent.firstOrNull { it.first == BusPaths.MEDIA_SYNC_XFER_FILE_REQUEST })
        assertEquals(MediaSyncResult.COMPLETED, finished?.result)
    }

    @Test
    fun `traffic for a stale session is ignored`() {
        val data = bytes(64, seed = 5L)
        val item = MediaSyncItem("img-e.jpg", data.size.toLong(), 5L)
        val receiver = receiver()
        receiver.deliverCatalog(listOf(item))

        receiver.onEnvelope(
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            MediaSyncTransferContract.chunkMeta("other-session", item.name, 0, 0L),
            data,
        )

        assertEquals(0L, staging.receivedBytes(item.name))
    }
}
