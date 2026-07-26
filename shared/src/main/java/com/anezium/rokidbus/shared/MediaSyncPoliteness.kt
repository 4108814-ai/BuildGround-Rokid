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
