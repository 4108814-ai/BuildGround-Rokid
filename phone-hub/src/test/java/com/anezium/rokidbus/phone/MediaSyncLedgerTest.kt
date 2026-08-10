package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.mediasync.SyncLedger
import com.anezium.rokidbus.phone.mediasync.SyncLedgerCodec
import com.anezium.rokidbus.phone.mediasync.SyncLedgerEntry
import com.anezium.rokidbus.phone.mediasync.SyncLedgerStorage
import com.anezium.rokidbus.shared.MediaSyncCaptureType
import com.anezium.rokidbus.shared.MediaSyncItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncLedgerTest {
    private class MemoryStorage(var value: String? = null) : SyncLedgerStorage {
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
            writes += 1
        }
    }

    private val first = mediaItem("img-20260710-175956-a0-N1-2.jpg", 3_145_728L, 1_000L)
    private val second = mediaItem("vid-20260710-180402-a0-N1-2.mp4", 41_000_000L, 2_000L)

    @Test
    fun `pending is the catalog minus what has already been recorded`() {
        val ledger = SyncLedger(MemoryStorage())
        ledger.record(first, 10L)

        assertEquals(listOf(second), ledger.pending(listOf(first, second)))
    }

    @Test
    fun `pending keeps catalog order so the oldest capture travels first`() {
        val ledger = SyncLedger(MemoryStorage())

        assertEquals(listOf(first, second), ledger.pending(listOf(first, second)))
    }

    @Test
    fun `a truncated earlier attempt does not count as synced`() {
        val ledger = SyncLedger(MemoryStorage())
        ledger.record(first.copy(sizeBytes = 12L), 10L)

        assertFalse(ledger.contains(first))
        assertEquals(listOf(first), ledger.pending(listOf(first)))
    }

    @Test
    fun `the ledger is authoritative so a gallery deletion never resurrects a capture`() {
        val storage = MemoryStorage()
        SyncLedger(storage).record(first, 10L)

        // A fresh hub process, an empty gallery: the ledger alone decides.
        val reloaded = SyncLedger(storage)

        assertTrue(reloaded.contains(first))
        assertEquals(emptyList<MediaSyncItem>(), reloaded.pending(listOf(first)))
        assertEquals(1, reloaded.size())
    }

    @Test
    fun `clear resets the ledger and its storage`() {
        val storage = MemoryStorage()
        val ledger = SyncLedger(storage)
        ledger.record(first, 10L)
        ledger.record(second, 11L)

        ledger.clear()

        assertEquals(0, ledger.size())
        assertEquals(emptyList<SyncLedgerEntry>(), SyncLedgerCodec.decode(storage.value.orEmpty()))
    }

    @Test
    fun `codec round trips and tolerates junk`() {
        val entries = listOf(SyncLedgerEntry("a.jpg", 1L, 2L), SyncLedgerEntry("b.mp4", 3L, 4L))

        assertEquals(entries, SyncLedgerCodec.decode(SyncLedgerCodec.encode(entries)))
        assertEquals(emptyList<SyncLedgerEntry>(), SyncLedgerCodec.decode("""{"version":9}"""))
    }

    @Test
    fun `a corrupt store degrades to an empty ledger instead of crashing the hub`() {
        val ledger = SyncLedger(MemoryStorage("not json at all"))

        assertEquals(0, ledger.size())
        assertEquals(listOf(first), ledger.pending(listOf(first)))
    }

    @Test
    fun `re-recording the same capture does not duplicate the entry`() {
        val ledger = SyncLedger(MemoryStorage())
        ledger.record(first, 10L)
        ledger.record(first, 20L)

        assertEquals(1, ledger.size())
        assertEquals(20L, ledger.snapshot().single().syncedAtMillis)
    }

    private fun mediaItem(name: String, sizeBytes: Long, modifiedMillis: Long) = MediaSyncItem(
        name,
        sizeBytes,
        modifiedMillis,
        MediaSyncCaptureType.defaultFor(name),
    )
}
