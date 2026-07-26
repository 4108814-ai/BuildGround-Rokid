package com.anezium.rokidbus.shared

/**
 * Records when traffic that is *not* photo sync last crossed the bus.
 *
 * The bus is one Bluetooth RFCOMM channel shared by surfaces, input, camera control and everything
 * else. Photo sync is the only participant that moves megabytes, so it is the only one that has to
 * ask whether anyone else is talking before it does.
 */
class MediaSyncTrafficMonitor(private val clock: () -> Long) {
    @Volatile
    private var lastForeignAtMillis = Long.MIN_VALUE

    /** Called for every envelope crossing the link, in either direction, on any path. */
    fun note(path: String) {
        if (!BusPaths.isMediaSyncTransferPath(path)) lastForeignAtMillis = clock()
    }

    fun millisSinceForeignTraffic(): Long {
        val last = lastForeignAtMillis
        if (last == Long.MIN_VALUE) return Long.MAX_VALUE
        return (clock() - last).coerceAtLeast(0L)
    }
}

enum class MediaSyncPace {
    /** Send the next chunk now. */
    SEND,

    /** Someone else is using the link; wait and ask again. */
    YIELD,

    /** Stop this session politely and resume from the stored offset later. */
    ABORT,
}

/**
 * Whether photo sync may put its next chunk on the shared link.
 *
 * The owner's hard requirement is that the transfer must never degrade the link when anything else
 * needs it, so the rules are ordered by whose need is greater: a live camera session wins outright,
 * a dropped link ends the session, recent foreign traffic buys everyone else a quiet window, and
 * only an idle link gets a chunk.
 */
object MediaSyncPolitenessPolicy {
    /** A chunk occupies the link ~90 ms; wait out a comfortable multiple of that after others talk. */
    const val QUIET_THRESHOLD_MS = 400L

    /** How long to stand down once foreign traffic is seen. Long enough for a burst to finish. */
    const val YIELD_BACKOFF_MS = 1_500L

    /** Idle gap between our own chunks, so we never own the link for more than a short burst. */
    const val CHUNK_PACING_MS = 40L

    fun pace(
        cameraSessionActive: Boolean,
        linkUp: Boolean,
        millisSinceForeignTraffic: Long,
        quietThresholdMs: Long = QUIET_THRESHOLD_MS,
    ): MediaSyncPace = when {
        cameraSessionActive -> MediaSyncPace.ABORT
        !linkUp -> MediaSyncPace.ABORT
        millisSinceForeignTraffic < quietThresholdMs -> MediaSyncPace.YIELD
        else -> MediaSyncPace.SEND
    }
}

/**
 * Application-level flow control over a transport that acknowledges too early.
 *
 * `SppServerManager.send` returns when the frame reaches the socket, not when it reaches the air,
 * so "one chunk in flight" was never true: the kernel queue ran several chunks deep. Holding the
 * sender to a bounded lead over the receiver's acknowledged offset is what makes in-flight bytes
 * an actual quantity rather than a hope — which in turn is what makes a yield or an abort take
 * effect on the radio instead of only on the enqueue path.
 */
object MediaSyncWindowPolicy {
    /** True when a chunk starting at [nextOffset] would stay inside the window. */
    fun maySend(
        nextOffset: Long,
        ackedOffset: Long,
        windowBytes: Int = MediaSyncTransferContract.ACK_WINDOW_BYTES,
    ): Boolean = nextOffset - ackedOffset <= windowBytes

    /** The receiver acks on a fixed cadence, and always once it holds the last byte. */
    fun shouldAck(
        chunksSinceAck: Int,
        stagedBytes: Long,
        expectedBytes: Long,
        everyChunks: Int = MediaSyncTransferContract.ACK_EVERY_CHUNKS,
    ): Boolean = chunksSinceAck >= everyChunks || stagedBytes >= expectedBytes

    /** The terminator may only follow a fully acknowledged file. */
    fun mayFinish(ackedOffset: Long, sizeBytes: Long): Boolean = ackedOffset >= sizeBytes
}

/**
 * How much of a file still has to travel, given what the receiver already holds.
 *
 * Resume is mandatory rather than nice-to-have here: at ~0.36 MB/s a 100 MB video is a multi-minute
 * transfer that will be interrupted by a camera session or a link drop, and restarting it from zero
 * every time would mean it never completes.
 */
object MediaSyncResumePolicy {
    sealed interface Decision {
        /** Continue from [offset]; the receiver's partial data is still valid. */
        data class Resume(val offset: Long) : Decision

        /** Throw the partial away and start over — the source no longer matches it. */
        data object Restart : Decision

        /** The receiver already holds every byte; only verification and publish remain. */
        data object Complete : Decision
    }

    fun decide(sourceSizeBytes: Long, receivedBytes: Long): Decision = when {
        receivedBytes <= 0L -> Decision.Resume(0L)
        // More bytes than the source has, so the partial belongs to a different file with a
        // recycled name: it can never hash correctly, and keeping it would corrupt the result.
        receivedBytes > sourceSizeBytes -> Decision.Restart
        receivedBytes == sourceSizeBytes -> Decision.Complete
        else -> Decision.Resume(receivedBytes)
    }
}
