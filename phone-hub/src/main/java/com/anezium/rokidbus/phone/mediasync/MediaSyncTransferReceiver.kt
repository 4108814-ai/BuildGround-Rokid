package com.anezium.rokidbus.phone.mediasync

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncItem
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncResumePolicy
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import org.json.JSONObject

/**
 * Drives one media-sync session from the phone, over the Bluetooth bus.
 *
 * The phone still pulls: ask for the catalog, diff it against the ledger, then request one file at
 * a time and ack it only once its bytes are staged, hashed, verified and published. What the bus
 * transport adds is resume — a file request carries the offset already held, so a transfer
 * interrupted by a camera session or a link drop continues instead of starting over. At ~0.36 MB/s
 * that is the difference between a 100 MB video finishing and never finishing.
 */
internal class MediaSyncTransferReceiver(
    private val sessionId: String,
    private val ledger: SyncLedger,
    private val gallery: MediaSyncGalleryWriter,
    private val staging: MediaSyncStagingStore,
    private val deleteAfterSync: Boolean,
    private val clock: () -> Long,
    private val logger: (String) -> Unit,
    private val send: (String, JSONObject) -> Boolean,
    private val onProgress: (MediaSyncProgress) -> Unit,
    private val onDeletionOutcome: (Boolean) -> Unit,
    private val onFinished: (MediaSyncRun) -> Unit,
) {
    private var pending: List<MediaSyncItem> = emptyList()
    private var index = -1
    private var totalBytes = 0L
    private var truncated = false
    private var finished = false

    private var filesSynced = 0
    private var filesFailed = 0
    private var filesDeleted = 0
    private var bytesSynced = 0L

    private var current: MediaSyncItem? = null
    private var expectedOffset = 0L
    private var lastProgressAtMillis = 0L
    private var awaitingDeleteResult = false

    fun start() {
        send(BusPaths.MEDIA_SYNC_XFER_CATALOG_REQUEST, MediaSyncTransferContract.sessionJson(sessionId))
    }

    fun onEnvelope(path: String, payload: JSONObject, binary: ByteArray?) {
        if (finished) return
        if (!MediaSyncTransferContract.isForSession(payload, sessionId)) return
        when (path) {
            BusPaths.MEDIA_SYNC_XFER_CATALOG -> onCatalog(payload)
            BusPaths.MEDIA_SYNC_XFER_FILE_BEGIN -> onFileBegin(payload)
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK -> onChunk(payload, binary)
            BusPaths.MEDIA_SYNC_XFER_FILE_END -> onFileEnd(payload)
            BusPaths.MEDIA_SYNC_XFER_FILE_ERROR -> onFileError(payload)
            BusPaths.MEDIA_SYNC_XFER_DELETE_RESULT -> onDeleteResult(payload)
            BusPaths.MEDIA_SYNC_XFER_ABORT -> onAbort(payload)
            else -> Unit
        }
    }

    /** The link died or the hub is shutting down: keep the partials, report what happened. */
    fun onLinkLost() = finish(
        if (filesSynced > 0) MediaSyncResult.PARTIAL else MediaSyncResult.FAILED,
        "Interrupted",
    )

    private fun onCatalog(payload: JSONObject) {
        val catalog = MediaSyncCatalogContract.decode(payload)
        if (catalog == null) {
            logger("mediaSync catalog malformed")
            finish(MediaSyncResult.FAILED, "Could not read the glasses catalog")
            return
        }
        truncated = catalog.truncated
        pending = ledger.pending(catalog.items)
        totalBytes = pending.sumOf(MediaSyncItem::sizeBytes)
        logger("mediaSync pending=${pending.size} bytes=$totalBytes truncated=$truncated")
        if (pending.isEmpty()) {
            send(BusPaths.MEDIA_SYNC_XFER_BYE, MediaSyncTransferContract.sessionJson(sessionId))
            finish(MediaSyncResult.UP_TO_DATE, null)
            return
        }
        requestNext()
    }

    private fun requestNext() {
        if (finished) return
        index += 1
        if (index >= pending.size) {
            send(BusPaths.MEDIA_SYNC_XFER_BYE, MediaSyncTransferContract.sessionJson(sessionId))
            finish(
                if (filesFailed == 0) MediaSyncResult.COMPLETED else MediaSyncResult.PARTIAL,
                if (truncated) "More captures remain; sync again" else null,
            )
            return
        }
        val item = pending[index]
        current = item
        publishProgress(force = true)
        if (gallery.alreadyPublished(item.name, item.sizeBytes)) {
            // Hi Rokid imported this capture manually before we ever saw it; adopt it into the
            // ledger instead of writing a duplicate into the same gallery bucket.
            logger("mediaSync adopting existing gallery file name=${item.name}")
            staging.discard(item.name)
            ledger.record(item, clock())
            filesSynced += 1
            requestNext()
            return
        }
        val staged = staging.receivedBytes(item.name)
        val offset = when (val decision = MediaSyncResumePolicy.decide(item.sizeBytes, staged)) {
            is MediaSyncResumePolicy.Decision.Restart -> {
                logger("mediaSync staged partial no longer matches name=${item.name}; restarting")
                staging.discard(item.name)
                0L
            }
            is MediaSyncResumePolicy.Decision.Complete -> {
                // Every byte is already here; only verification and publish remain, but the digest
                // lives on the glasses side, so ask for the tail-less transfer and let FILE_END
                // deliver it.
                item.sizeBytes
            }
            is MediaSyncResumePolicy.Decision.Resume -> decision.offset
        }
        if (offset > 0L) logger("mediaSync resuming name=${item.name} offset=$offset")
        expectedOffset = offset
        send(
            BusPaths.MEDIA_SYNC_XFER_FILE_REQUEST,
            MediaSyncTransferContract.fileRequest(sessionId, item.name, offset),
        )
    }

    private fun onFileBegin(payload: JSONObject) {
        val item = current ?: return
        if (MediaSyncTransferContract.name(payload) != item.name) return
        val offset = MediaSyncTransferContract.offset(payload)
        if (offset != expectedOffset) {
            // The glasses are starting somewhere else than we asked; drop what we hold so the
            // staged bytes can never end up with a hole in the middle.
            logger("mediaSync begin offset mismatch name=${item.name} want=$expectedOffset got=$offset")
            staging.discard(item.name)
            expectedOffset = offset
        }
    }

    private fun onChunk(payload: JSONObject, binary: ByteArray?) {
        val item = current ?: return
        val data = binary ?: return
        if (MediaSyncTransferContract.name(payload) != item.name) return
        val offset = MediaSyncTransferContract.offset(payload)
        if (offset != expectedOffset) {
            // RFCOMM is ordered, so a gap means a genuinely lost or duplicated frame. Refuse the
            // chunk rather than staging bytes at the wrong position.
            logger("mediaSync chunk out of order name=${item.name} want=$expectedOffset got=$offset")
            return
        }
        if (!staging.append(item.name, data, data.size)) {
            logger("mediaSync staging write failed name=${item.name}")
            failCurrent()
            return
        }
        expectedOffset += data.size
        bytesSynced += data.size
        publishProgress(force = false)
    }

    private fun onFileEnd(payload: JSONObject) {
        val item = current ?: return
        if (MediaSyncTransferContract.name(payload) != item.name) return
        val expectedSha = payload.optString("sha256")
        val actualSha = staging.sha256(item.name)
        if (expectedSha.isBlank() || actualSha == null || !actualSha.equals(expectedSha, true)) {
            logger("mediaSync checksum mismatch name=${item.name}")
            staging.discard(item.name)
            acknowledge(item.name, ok = false)
            failCurrent()
            return
        }
        if (!publishToGallery(item, expectedSha)) {
            acknowledge(item.name, ok = false)
            failCurrent()
            return
        }
        staging.discard(item.name)
        ledger.record(item, clock())
        filesSynced += 1
        acknowledge(item.name, ok = true)
        if (deleteAfterSync) {
            awaitingDeleteResult = true
            // The glasses answer with a delete result; requestNext waits for it so the two files
            // never interleave their deletions.
            return
        }
        requestNext()
    }

    private fun publishToGallery(item: MediaSyncItem, sha256: String): Boolean {
        val transfer = gallery.open(item.name)
        if (transfer == null) {
            logger("mediaSync gallery row unavailable name=${item.name}")
            return false
        }
        val streamed = staging.readStaged(item.name) { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                transfer.append(buffer, read)
            }
            true
        } ?: false
        if (!streamed) {
            transfer.discard()
            logger("mediaSync staging read failed name=${item.name}")
            return false
        }
        val capturedAt = MediaSyncGalleryTarget.capturedAtMillis(item.name, item.modifiedMillis)
        // The gallery writer hashes what it was handed and refuses to publish on a mismatch, so
        // the copy out of staging is verified too, not just the transfer.
        return transfer.publish(sha256, capturedAt)
    }

    private fun onFileError(payload: JSONObject) {
        val item = current ?: return
        if (MediaSyncTransferContract.name(payload) != item.name) return
        val code = payload.optString("code")
        logger("mediaSync glasses refused name=${item.name} code=$code")
        if (code == MediaSyncTransferContract.ERROR_CHANGED) staging.discard(item.name)
        failCurrent()
    }

    private fun onDeleteResult(payload: JSONObject) {
        val outcome = payload.optString("outcome")
        logger("mediaSync delete outcome=$outcome")
        when (outcome) {
            OUTCOME_DELETED -> {
                filesDeleted += 1
                onDeletionOutcome(true)
            }
            OUTCOME_ALREADY_GONE -> onDeletionOutcome(true)
            OUTCOME_NOT_PERMITTED -> onDeletionOutcome(false)
        }
        if (awaitingDeleteResult) {
            awaitingDeleteResult = false
            requestNext()
        }
    }

    private fun onAbort(payload: JSONObject) {
        val reason = payload.optString("reason")
        logger("mediaSync aborted by glasses reason=$reason")
        finish(
            if (filesSynced > 0) MediaSyncResult.PARTIAL else MediaSyncResult.FAILED,
            when (reason) {
                MediaSyncTransferContract.ABORT_CAMERA -> "Paused for the camera"
                else -> "Interrupted"
            },
        )
    }

    private fun acknowledge(name: String, ok: Boolean) {
        send(
            BusPaths.MEDIA_SYNC_XFER_FILE_ACK,
            MediaSyncTransferContract.fileAck(sessionId, name, ok, ok && deleteAfterSync),
        )
    }

    private fun failCurrent() {
        filesFailed += 1
        awaitingDeleteResult = false
        requestNext()
    }

    /**
     * Status pushes ride the same link the transfer is being polite about, so they are throttled:
     * once per file boundary, and otherwise no more often than [PROGRESS_INTERVAL_MS].
     */
    private fun publishProgress(force: Boolean) {
        val now = clock()
        if (!force && now - lastProgressAtMillis < PROGRESS_INTERVAL_MS) return
        lastProgressAtMillis = now
        onProgress(
            MediaSyncProgress(
                filesDone = index.coerceAtLeast(0),
                filesTotal = pending.size,
                bytesDone = bytesSynced,
                bytesTotal = totalBytes,
                currentFile = current?.name,
            ),
        )
    }

    private fun finish(result: MediaSyncResult, message: String?) {
        if (finished) return
        finished = true
        onFinished(
            MediaSyncRun(
                finishedAtMillis = clock(),
                result = result,
                filesSynced = filesSynced,
                bytesSynced = bytesSynced,
                filesFailed = filesFailed,
                filesDeleted = filesDeleted,
                message = message,
            ),
        )
    }

    private companion object {
        const val OUTCOME_DELETED = "deleted"
        const val OUTCOME_ALREADY_GONE = "already_gone"
        const val OUTCOME_NOT_PERMITTED = "not_permitted"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 2_000L
    }
}
