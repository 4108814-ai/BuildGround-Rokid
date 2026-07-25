package com.anezium.rokidbus.phone.mediasync

import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncItem
import com.anezium.rokidbus.shared.MediaSyncPacket
import com.anezium.rokidbus.shared.MediaSyncPacketType
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncProtocol
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Drives one media-sync data-plane session over an already-connected socket.
 *
 * The phone pulls: it asks for the catalog, diffs it against the ledger, then requests one file
 * at a time and acks each only after the bytes are hashed, verified and published to the gallery.
 * That ordering is the whole resume model for v1 — an interrupted file is simply never acked, so
 * the next run fetches it again from zero while completed files never travel twice.
 * Partial-file resume is deliberately future work.
 */
internal class MediaSyncTransferSession(
    private val socket: Socket,
    private val token: String,
    private val ledger: SyncLedger,
    private val gallery: MediaSyncGalleryWriter,
    private val deleteAfterSync: Boolean,
    private val clock: () -> Long,
    private val logger: (String) -> Unit,
    private val onProgress: (MediaSyncProgress) -> Unit,
    private val onDeletionOutcome: (Boolean) -> Unit,
) {
    private var filesSynced = 0
    private var filesFailed = 0
    private var filesDeleted = 0
    private var bytesSynced = 0L
    private var aborted = false
    private var abortReason: String? = null

    fun run(): MediaSyncRun {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        write(output, MediaSyncPacketType.HELLO, JSONObject().put("token", token))
        val ack = MediaSyncProtocol.read(input)
        if (ack?.type != MediaSyncPacketType.HELLO_ACK) {
            return failed("handshake refused")
        }
        write(output, MediaSyncPacketType.CATALOG_REQUEST, JSONObject())
        val catalogPacket = MediaSyncProtocol.read(input)
        if (catalogPacket?.type != MediaSyncPacketType.CATALOG) {
            return failed("catalog unavailable")
        }
        val catalog = runCatching { JSONObject(catalogPacket.meta) }
            .getOrNull()
            ?.let(MediaSyncCatalogContract::decode)
            ?: return failed("catalog malformed")

        val pending = ledger.pending(catalog.items)
        if (pending.isEmpty()) {
            write(output, MediaSyncPacketType.BYE, JSONObject())
            return MediaSyncRun(
                finishedAtMillis = clock(),
                result = MediaSyncResult.UP_TO_DATE,
                filesSynced = 0,
                bytesSynced = 0L,
                filesFailed = 0,
                filesDeleted = 0,
            )
        }
        val totalBytes = pending.sumOf(MediaSyncItem::sizeBytes)
        logger("mediaSync pending=${pending.size} bytes=$totalBytes truncated=${catalog.truncated}")
        pending.forEachIndexed { index, item ->
            onProgress(
                MediaSyncProgress(
                    filesDone = index,
                    filesTotal = pending.size,
                    bytesDone = bytesSynced,
                    bytesTotal = totalBytes,
                    currentFile = item.name,
                ),
            )
            if (gallery.alreadyPublished(item.name, item.sizeBytes)) {
                // Hi Rokid imported this capture manually before we ever saw it; adopt it into
                // the ledger instead of writing a duplicate into the same gallery bucket.
                logger("mediaSync adopting existing gallery file name=${item.name}")
                ledger.record(item, clock())
                filesSynced += 1
                return@forEachIndexed
            }
            if (!transferOne(input, output, item)) {
                if (aborted) return abortedRun()
                filesFailed += 1
            }
        }
        onProgress(
            MediaSyncProgress(
                filesDone = pending.size,
                filesTotal = pending.size,
                bytesDone = bytesSynced,
                bytesTotal = totalBytes,
            ),
        )
        write(output, MediaSyncPacketType.BYE, JSONObject())
        return MediaSyncRun(
            finishedAtMillis = clock(),
            result = if (filesFailed == 0) MediaSyncResult.COMPLETED else MediaSyncResult.PARTIAL,
            filesSynced = filesSynced,
            bytesSynced = bytesSynced,
            filesFailed = filesFailed,
            filesDeleted = filesDeleted,
            message = if (catalog.truncated) "More captures remain; sync again" else null,
        )
    }

    private fun transferOne(
        input: InputStream,
        output: OutputStream,
        item: MediaSyncItem,
    ): Boolean {
        write(output, MediaSyncPacketType.FILE_REQUEST, JSONObject().put("name", item.name))
        val begin = MediaSyncProtocol.read(input) ?: return false
        if (begin.type == MediaSyncPacketType.ABORT) return abort(begin)
        if (begin.type == MediaSyncPacketType.FILE_ERROR) {
            logger("mediaSync glasses refused name=${item.name} meta=${begin.meta}")
            return false
        }
        if (begin.type != MediaSyncPacketType.FILE_BEGIN) return false
        val transfer = gallery.open(item.name) ?: return false
        var received = 0L
        var published = false
        try {
            while (true) {
                val packet = MediaSyncProtocol.read(input) ?: return false
                when (packet.type) {
                    MediaSyncPacketType.FILE_CHUNK -> {
                        transfer.append(packet.payload, packet.payload.size)
                        received += packet.payload.size
                    }
                    MediaSyncPacketType.FILE_END -> {
                        val sha = runCatching { JSONObject(packet.meta).optString("sha256") }
                            .getOrDefault("")
                        val capturedAt = MediaSyncGalleryTarget
                            .capturedAtMillis(item.name, item.modifiedMillis)
                        published = sha.isNotBlank() && transfer.publish(sha, capturedAt)
                        if (published) {
                            bytesSynced += received
                            filesSynced += 1
                            ledger.record(item, clock())
                        }
                        acknowledge(output, item.name, published)
                        if (published && deleteAfterSync) readDeleteResult(input)
                        return published
                    }
                    MediaSyncPacketType.FILE_ERROR -> {
                        logger("mediaSync transfer error name=${item.name} meta=${packet.meta}")
                        return false
                    }
                    MediaSyncPacketType.ABORT -> return abort(packet)
                    else -> logger("mediaSync unexpected packet type=${packet.type}")
                }
            }
        } finally {
            if (!published) transfer.discard()
        }
    }

    private fun acknowledge(output: OutputStream, name: String, ok: Boolean) {
        write(
            output,
            MediaSyncPacketType.FILE_ACK,
            JSONObject()
                .put("name", name)
                .put("ok", ok)
                .put("delete", ok && deleteAfterSync),
        )
    }

    private fun readDeleteResult(input: InputStream) {
        val packet = MediaSyncProtocol.read(input) ?: return
        if (packet.type != MediaSyncPacketType.DELETE_RESULT) return
        val outcome = runCatching { JSONObject(packet.meta).optString("outcome") }.getOrDefault("")
        logger("mediaSync delete outcome=$outcome")
        when (outcome) {
            OUTCOME_DELETED -> {
                filesDeleted += 1
                onDeletionOutcome(true)
            }
            OUTCOME_ALREADY_GONE -> onDeletionOutcome(true)
            OUTCOME_NOT_PERMITTED -> onDeletionOutcome(false)
        }
    }

    private fun abort(packet: MediaSyncPacket): Boolean {
        aborted = true
        abortReason = runCatching { JSONObject(packet.meta).optString("reason") }.getOrNull()
        logger("mediaSync aborted by glasses reason=$abortReason")
        return false
    }

    private fun abortedRun(): MediaSyncRun = MediaSyncRun(
        finishedAtMillis = clock(),
        result = if (filesSynced > 0) MediaSyncResult.PARTIAL else MediaSyncResult.FAILED,
        filesSynced = filesSynced,
        bytesSynced = bytesSynced,
        filesFailed = filesFailed,
        filesDeleted = filesDeleted,
        message = when (abortReason) {
            "camera_active" -> "Paused for the camera"
            else -> "Interrupted"
        },
    )

    private fun failed(message: String): MediaSyncRun = MediaSyncRun(
        finishedAtMillis = clock(),
        result = MediaSyncResult.FAILED,
        filesSynced = filesSynced,
        bytesSynced = bytesSynced,
        filesFailed = filesFailed,
        filesDeleted = filesDeleted,
        message = message,
    )

    private fun write(output: OutputStream, type: MediaSyncPacketType, meta: JSONObject) {
        MediaSyncProtocol.write(output, MediaSyncPacket(type = type, meta = meta.toString()))
    }

    private companion object {
        const val OUTCOME_DELETED = "deleted"
        const val OUTCOME_ALREADY_GONE = "already_gone"
        const val OUTCOME_NOT_PERMITTED = "not_permitted"
    }
}
