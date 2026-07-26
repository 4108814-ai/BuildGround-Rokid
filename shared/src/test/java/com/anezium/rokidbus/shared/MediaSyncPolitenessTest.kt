package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncPolitenessTest {
    private var now = 10_000L
    private val monitor = MediaSyncTrafficMonitor { now }

    private fun pace(
        camera: Boolean = false,
        linkUp: Boolean = true,
        sinceForeign: Long = Long.MAX_VALUE,
    ) = MediaSyncPolitenessPolicy.pace(camera, linkUp, sinceForeign)

    @Test
    fun `an idle link gets the chunk`() {
        assertEquals(MediaSyncPace.SEND, pace())
    }

    @Test
    fun `recent foreign traffic buys everyone else a quiet window`() {
        assertEquals(MediaSyncPace.YIELD, pace(sinceForeign = 0L))
        assertEquals(
            MediaSyncPace.YIELD,
            pace(sinceForeign = MediaSyncPolitenessPolicy.QUIET_THRESHOLD_MS - 1),
        )
        assertEquals(
            MediaSyncPace.SEND,
            pace(sinceForeign = MediaSyncPolitenessPolicy.QUIET_THRESHOLD_MS),
        )
    }

    @Test
    fun `a camera session outranks everything, even a perfectly idle link`() {
        assertEquals(MediaSyncPace.ABORT, pace(camera = true))
        assertEquals(MediaSyncPace.ABORT, pace(camera = true, sinceForeign = Long.MAX_VALUE))
    }

    @Test
    fun `a dropped link ends the session rather than spinning`() {
        assertEquals(MediaSyncPace.ABORT, pace(linkUp = false))
    }

    @Test
    fun `the monitor ignores our own transfer traffic`() {
        monitor.note(BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK)
        monitor.note(BusPaths.MEDIA_SYNC_XFER_CATALOG)

        // Never observed anyone else, so the link counts as quiet forever.
        assertEquals(Long.MAX_VALUE, monitor.millisSinceForeignTraffic())
    }

    @Test
    fun `the monitor notices everyone else`() {
        monitor.note(BusPaths.SURFACE_UPDATE)
        assertEquals(0L, monitor.millisSinceForeignTraffic())

        now += 250L
        assertEquals(250L, monitor.millisSinceForeignTraffic())
        assertEquals(MediaSyncPace.YIELD, pace(sinceForeign = monitor.millisSinceForeignTraffic()))

        now += 250L
        assertEquals(MediaSyncPace.SEND, pace(sinceForeign = monitor.millisSinceForeignTraffic()))
    }

    @Test
    fun `control traffic on our own namespace still counts as foreign`() {
        // Status pushes and the settings plane are not the bulk transfer; if they are flowing,
        // something is actually happening and the chunk can wait its turn.
        monitor.note(BusPaths.MEDIA_SYNC_STATUS)

        assertEquals(0L, monitor.millisSinceForeignTraffic())
    }

    @Test
    fun `pacing numbers stay in the range the measured link can absorb`() {
        // 32 KiB occupies the link roughly 90 ms at the measured 64 KiB / 180 ms.
        assertEquals(32 * 1024, MediaSyncTransferContract.CHUNK_BYTES)
        assertTrue(MediaSyncTransferContract.CHUNK_BYTES <= MediaSyncTransferContract.MAX_CHUNK_BYTES)
        assertTrue(MediaSyncPolitenessPolicy.CHUNK_PACING_MS in 1..200)
        assertTrue(MediaSyncPolitenessPolicy.YIELD_BACKOFF_MS >= MediaSyncPolitenessPolicy.QUIET_THRESHOLD_MS)
    }

    @Test
    fun `the sender may run exactly one window ahead, never further`() {
        val window = MediaSyncTransferContract.ACK_WINDOW_BYTES

        assertTrue(MediaSyncWindowPolicy.maySend(nextOffset = 0L, ackedOffset = 0L))
        assertTrue(MediaSyncWindowPolicy.maySend(window.toLong(), ackedOffset = 0L))
        // One byte past the window is where the socket queue would start growing unbounded.
        assertFalse(MediaSyncWindowPolicy.maySend(window + 1L, ackedOffset = 0L))
        assertTrue(MediaSyncWindowPolicy.maySend(window + 1L, ackedOffset = 1L))
    }

    @Test
    fun `the window is a handful of chunks, not a whole file`() {
        val chunksPerWindow =
            MediaSyncTransferContract.ACK_WINDOW_BYTES / MediaSyncTransferContract.CHUNK_BYTES

        assertEquals(4, chunksPerWindow)
        // The receiver must ack more often than the window is deep, or the sender starves.
        assertTrue(MediaSyncTransferContract.ACK_EVERY_CHUNKS < chunksPerWindow)
    }

    @Test
    fun `the receiver acks on cadence and always on the last byte`() {
        val every = MediaSyncTransferContract.ACK_EVERY_CHUNKS

        assertFalse(MediaSyncWindowPolicy.shouldAck(0, stagedBytes = 10L, expectedBytes = 100L))
        assertTrue(MediaSyncWindowPolicy.shouldAck(every, stagedBytes = 10L, expectedBytes = 100L))
        // The tail rarely lands on the cadence, so completeness has to force an ack of its own -
        // otherwise the sender would wait for an ack that never comes and time out.
        assertTrue(MediaSyncWindowPolicy.shouldAck(1, stagedBytes = 100L, expectedBytes = 100L))
        assertTrue(MediaSyncWindowPolicy.shouldAck(0, stagedBytes = 101L, expectedBytes = 100L))
    }

    @Test
    fun `the terminator waits for the last byte to be acknowledged`() {
        // This is the rule that removes the two-channel race: FILE_END travels the control channel
        // and could otherwise overtake chunks still queued on SPP.
        assertFalse(MediaSyncWindowPolicy.mayFinish(ackedOffset = 999L, sizeBytes = 1_000L))
        assertTrue(MediaSyncWindowPolicy.mayFinish(ackedOffset = 1_000L, sizeBytes = 1_000L))
        assertTrue(MediaSyncWindowPolicy.mayFinish(ackedOffset = 1_001L, sizeBytes = 1_000L))
    }

    @Test
    fun `a fully resumed file may finish without any chunk being sent`() {
        // The sender seeds its acked offset from the requested offset, so a file the phone already
        // holds in full goes straight to verification.
        assertTrue(MediaSyncWindowPolicy.mayFinish(ackedOffset = 4_096L, sizeBytes = 4_096L))
    }

    @Test
    fun `resume continues from what the receiver already holds`() {
        assertEquals(
            MediaSyncResumePolicy.Decision.Resume(0L),
            MediaSyncResumePolicy.decide(sourceSizeBytes = 1_000L, receivedBytes = 0L),
        )
        assertEquals(
            MediaSyncResumePolicy.Decision.Resume(400L),
            MediaSyncResumePolicy.decide(sourceSizeBytes = 1_000L, receivedBytes = 400L),
        )
    }

    @Test
    fun `a partial that outgrew its source is thrown away, never resumed`() {
        // A recycled capture name with a shorter file: resuming would splice two files together.
        assertEquals(
            MediaSyncResumePolicy.Decision.Restart,
            MediaSyncResumePolicy.decide(sourceSizeBytes = 1_000L, receivedBytes = 1_001L),
        )
    }

    @Test
    fun `a complete partial only needs verifying`() {
        assertEquals(
            MediaSyncResumePolicy.Decision.Complete,
            MediaSyncResumePolicy.decide(sourceSizeBytes = 1_000L, receivedBytes = 1_000L),
        )
    }

    @Test
    fun `negative or absent progress is treated as nothing received`() {
        assertEquals(
            MediaSyncResumePolicy.Decision.Resume(0L),
            MediaSyncResumePolicy.decide(sourceSizeBytes = 10L, receivedBytes = -5L),
        )
    }
}
