package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncPace
import com.anezium.rokidbus.shared.MediaSyncPolitenessPolicy
import com.anezium.rokidbus.shared.MediaSyncTrafficMonitor
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MediaSyncServerSummary(
    val filesServed: Int,
    val bytesServed: Long,
    val filesDeleted: Int,
    val deletionRefused: Boolean,
    val abortReason: String?,
)

/**
 * Serves the capture catalog and capture bytes over the Bluetooth bus.
 *
 * The phone still drives: it asks for the catalog, then pulls one file at a time and acks each
 * only once the bytes are hashed, verified and published. What changed with the transport is that
 * the link is now shared with everything else the glasses do, so every chunk is gated on the
 * politeness policy — a live camera session stops the transfer outright, and any other traffic
 * buys itself a quiet window before we speak again.
 */
internal class MediaSyncTransferSender(
    private val sessionId: String,
    private val catalog: MediaCatalog,
    private val deletionExecutor: MediaSyncDeletionExecutor,
    private val isCameraSessionActive: () -> Boolean,
    private val isLinkUp: () -> Boolean,
    private val trafficMonitor: MediaSyncTrafficMonitor,
    private val send: (String, JSONObject, ByteArray?) -> Boolean,
    private val logger: (String) -> Unit,
    private val onFinished: (MediaSyncServerSummary) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-sync-send").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    @Volatile private var served = 0
    @Volatile private var bytes = 0L
    @Volatile private var deleted = 0
    @Volatile private var deletionRefused = false
    @Volatile private var streaming = false

    fun onCatalogRequest() = executor.execute {
        if (closed.get()) return@execute
        val scan = catalog.scan()
        logger("catalog items=${scan.items.size} truncated=${scan.truncated}")
        send(
            BusPaths.MEDIA_SYNC_XFER_CATALOG,
            MediaSyncCatalogContract.encode(scan.items, scan.truncated)
                .put("version", MediaSyncTransferContract.VERSION)
                .put("sessionId", sessionId)
                .put("catalogVersion", MediaSyncCatalogContract.VERSION),
            null,
        )
    }

    fun onFileRequest(name: String, offset: Long) = executor.execute {
        if (closed.get()) return@execute
        streamFile(name, offset)
    }

    fun onFileAck(name: String, ok: Boolean, delete: Boolean) = executor.execute {
        if (closed.get()) return@execute
        if (!ok) {
            logger("file rejected by phone name=$name")
            return@execute
        }
        served += 1
        if (!delete) return@execute
        val outcome = deletionExecutor.delete(name)
        when (outcome) {
            MediaSyncDeletionOutcome.DELETED -> deleted += 1
            MediaSyncDeletionOutcome.NOT_PERMITTED -> deletionRefused = true
            else -> Unit
        }
        logger("delete name=$name outcome=${outcome.wireValue}")
        send(
            BusPaths.MEDIA_SYNC_XFER_DELETE_RESULT,
            MediaSyncTransferContract.deleteResult(sessionId, name, outcome.wireValue),
            null,
        )
    }

    fun onBye() = executor.execute { finish(null) }

    /** The camera took the glasses; stop mid-file and let the phone resume from its offset. */
    fun onCameraSessionOpened() {
        if (!streaming) executor.execute { abort(MediaSyncTransferContract.ABORT_CAMERA) }
        // A stream in flight notices through the politeness policy on its next chunk.
    }

    private fun streamFile(name: String, requestedOffset: Long) {
        val file = catalog.resolve(name)
        if (file == null) {
            sendError(name, MediaSyncTransferContract.ERROR_NOT_FOUND)
            return
        }
        val size = file.length()
        if (requestedOffset > size) {
            // The phone holds more bytes than the file has: the name was recycled.
            sendError(name, MediaSyncTransferContract.ERROR_CHANGED)
            return
        }
        send(
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN,
            MediaSyncTransferContract.fileBegin(sessionId, name, size, file.lastModified(), requestedOffset),
            null,
        )
        streaming = true
        try {
            val digest = MediaSyncTransferContract.newDigest()
            val buffer = ByteArray(MediaSyncTransferContract.CHUNK_BYTES)
            var position = 0L
            var seq = 0
            val streamed = runCatching {
                file.inputStream().use { stream: InputStream ->
                    // The end-of-file digest always covers the whole file, so bytes already on the
                    // phone are still read and hashed here - they simply are not re-sent.
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        val chunkEnd = position + read
                        if (chunkEnd > requestedOffset) {
                            val from = maxOf(0, (requestedOffset - position).toInt())
                            if (!sendChunk(name, seq, position + from, buffer, from, read - from)) {
                                return
                            }
                            seq += 1
                        }
                        position = chunkEnd
                    }
                }
            }.onFailure { logger("read failed name=$name error=${it.message}") }.isSuccess
            if (!streamed || position != size) {
                sendError(name, MediaSyncTransferContract.ERROR_READ_FAILED)
                return
            }
            send(
                BusPaths.MEDIA_SYNC_XFER_FILE_END,
                MediaSyncTransferContract.fileEnd(sessionId, name, MediaSyncTransferContract.hex(digest)),
                null,
            )
        } finally {
            streaming = false
        }
    }

    /** Returns false when the session ended underneath us and streaming must stop. */
    private fun sendChunk(
        name: String,
        seq: Int,
        offset: Long,
        buffer: ByteArray,
        from: Int,
        length: Int,
    ): Boolean {
        var failures = 0
        while (!closed.get()) {
            when (
                MediaSyncPolitenessPolicy.pace(
                    cameraSessionActive = isCameraSessionActive(),
                    linkUp = isLinkUp(),
                    millisSinceForeignTraffic = trafficMonitor.millisSinceForeignTraffic(),
                )
            ) {
                MediaSyncPace.ABORT -> {
                    val reason = if (isCameraSessionActive()) {
                        MediaSyncTransferContract.ABORT_CAMERA
                    } else {
                        MediaSyncTransferContract.ABORT_LINK
                    }
                    abort(reason)
                    return false
                }
                MediaSyncPace.YIELD -> {
                    if (!sleep(MediaSyncPolitenessPolicy.YIELD_BACKOFF_MS)) return false
                }
                MediaSyncPace.SEND -> {
                    val payload = if (from == 0 && length == buffer.size) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOfRange(from, from + length)
                    }
                    // send() blocks until the frame is on the socket, so a returning call is the
                    // transport's own confirmation: exactly one chunk is ever in flight.
                    val ok = send(
                        BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
                        MediaSyncTransferContract.chunkMeta(sessionId, name, seq, offset),
                        payload,
                    )
                    if (ok) {
                        bytes += length
                        return sleep(MediaSyncPolitenessPolicy.CHUNK_PACING_MS)
                    }
                    failures += 1
                    if (failures >= MAX_CHUNK_SEND_FAILURES) {
                        abort(MediaSyncTransferContract.ABORT_LINK)
                        return false
                    }
                    if (!sleep(MediaSyncPolitenessPolicy.YIELD_BACKOFF_MS)) return false
                }
            }
        }
        return false
    }

    private fun sleep(millis: Long): Boolean =
        runCatching { Thread.sleep(millis) }.isSuccess && !closed.get()

    private fun sendError(name: String, code: String) {
        send(
            BusPaths.MEDIA_SYNC_XFER_FILE_ERROR,
            MediaSyncTransferContract.fileError(sessionId, name, code),
            null,
        )
    }

    private fun abort(reason: String) {
        logger("aborting session reason=$reason")
        send(BusPaths.MEDIA_SYNC_XFER_ABORT, MediaSyncTransferContract.abort(sessionId, reason), null)
        finish(reason)
    }

    private fun finish(abortReason: String?) {
        if (closed.get()) return
        val summary = MediaSyncServerSummary(served, bytes, deleted, deletionRefused, abortReason)
        served = 0
        bytes = 0L
        deleted = 0
        deletionRefused = false
        runCatching { onFinished(summary) }
            .onFailure { logger("summary failed error=${it.message}") }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_CHUNK_SEND_FAILURES = 3
    }
}
