package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncPacket
import com.anezium.rokidbus.shared.MediaSyncPacketType
import com.anezium.rokidbus.shared.MediaSyncProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
 * Serves the capture catalog and capture bytes over the media-sync Wi-Fi Direct group.
 *
 * The phone drives: it asks for the catalog, then pulls one file at a time and acks each one.
 * Pull-shaped transfers give per-file atomicity for free — an interrupted file simply is not
 * acked, so the next session re-requests it from zero and a completed file never travels twice.
 *
 * One client at a time; the accept loop survives a client crash exactly like the camera link's.
 */
internal class MediaSyncFileServer(
    private val catalog: MediaCatalog,
    private val token: String,
    private val deletionExecutor: MediaSyncDeletionExecutor,
    private val isCameraSessionActive: () -> Boolean,
    private val logger: (String) -> Unit,
    private val onClientAuthenticated: () -> Unit,
    private val onSessionFinished: (MediaSyncServerSummary) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-sync-accept").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var served = 0

    @Volatile
    private var bytes = 0L

    @Volatile
    private var deleted = 0

    @Volatile
    private var deletionRefused = false

    @Volatile
    private var authenticated = false

    /** Binds the data plane and returns the listening port, or null when the bind failed. */
    fun start(address: InetAddress): Int? {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, PORT))
            }
        }.onFailure { logger("mediaSync server bind failed error=${it.message}") }.getOrNull()
            ?: return null
        server = socket
        executor.execute { acceptLoop(socket) }
        return socket.localPort
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed.get() && !socket.isClosed) {
            val accepted = runCatching { socket.accept() }.getOrNull() ?: continue
            client = accepted
            runCatching { handleClient(accepted) }
                .onFailure { logger("mediaSync server client ended error=${it.message}") }
            runCatching { accepted.close() }
            client = null
            // A client that never got past the token check is not a session; reporting it would
            // tell the phone a sync ended when none ever started.
            if (authenticated) reportSession(abortReason = null)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val hello = MediaSyncProtocol.read(input) ?: return
        if (hello.type != MediaSyncPacketType.HELLO) {
            logger("mediaSync server rejected first packet type=${hello.type}")
            return
        }
        val offered = runCatching { JSONObject(hello.meta).optString("token") }.getOrDefault("")
        if (!MediaSyncProtocol.tokensMatch(token, offered)) {
            logger("mediaSync server rejected client reason=token")
            return
        }
        write(output, MediaSyncPacketType.HELLO_ACK, JSONObject().put("ok", true))
        authenticated = true
        logger("mediaSync server authenticated")
        runCatching(onClientAuthenticated)
            .onFailure { logger("mediaSync server auth callback failed error=${it.message}") }
        while (!closed.get()) {
            if (isCameraSessionActive()) {
                write(output, MediaSyncPacketType.ABORT, JSONObject().put("reason", ABORT_CAMERA))
                reportSession(abortReason = ABORT_CAMERA)
                return
            }
            val packet = MediaSyncProtocol.read(input) ?: return
            when (packet.type) {
                MediaSyncPacketType.CATALOG_REQUEST -> sendCatalog(output)
                MediaSyncPacketType.FILE_REQUEST -> sendFile(output, packet)
                MediaSyncPacketType.FILE_ACK -> handleAck(output, packet)
                MediaSyncPacketType.BYE -> return
                else -> logger("mediaSync server ignoring packet type=${packet.type}")
            }
        }
    }

    private fun sendCatalog(output: OutputStream) {
        val scan = catalog.scan()
        write(
            output,
            MediaSyncPacketType.CATALOG,
            MediaSyncCatalogContract.encode(scan.items, scan.truncated),
        )
        logger("mediaSync server catalog items=${scan.items.size} truncated=${scan.truncated}")
    }

    private fun sendFile(output: OutputStream, packet: MediaSyncPacket) {
        val name = runCatching { JSONObject(packet.meta).optString("name") }.getOrDefault("")
        val file = catalog.resolve(name)
        if (file == null) {
            write(
                output,
                MediaSyncPacketType.FILE_ERROR,
                JSONObject().put("name", name).put("code", "not_found"),
            )
            return
        }
        val length = file.length()
        write(
            output,
            MediaSyncPacketType.FILE_BEGIN,
            JSONObject()
                .put("name", name)
                .put("size", length)
                .put("mtime", file.lastModified()),
        )
        val digest = MediaSyncProtocol.newDigest()
        val buffer = ByteArray(MediaSyncProtocol.CHUNK_BYTES)
        var sequence = 0
        var sent = 0L
        val streamed = runCatching {
            file.inputStream().use { stream: InputStream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    MediaSyncProtocol.write(
                        output,
                        MediaSyncPacket(
                            type = MediaSyncPacketType.FILE_CHUNK,
                            seq = sequence,
                            payload = buffer.copyOf(read),
                        ),
                    )
                    sequence += 1
                    sent += read
                }
            }
        }.onFailure { logger("mediaSync server read failed name=$name error=${it.message}") }
            .isSuccess
        if (!streamed || sent != length) {
            write(
                output,
                MediaSyncPacketType.FILE_ERROR,
                JSONObject().put("name", name).put("code", "read_failed"),
            )
            return
        }
        write(
            output,
            MediaSyncPacketType.FILE_END,
            JSONObject().put("name", name).put("sha256", MediaSyncProtocol.hex(digest)),
        )
        bytes += sent
    }

    private fun handleAck(output: OutputStream, packet: MediaSyncPacket) {
        val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
        val name = meta.optString("name")
        if (name.isBlank()) return
        if (!meta.optBoolean("ok")) {
            logger("mediaSync server file rejected by phone name=$name")
            return
        }
        served += 1
        if (!meta.optBoolean("delete")) return
        val outcome = deletionExecutor.delete(name)
        when (outcome) {
            MediaSyncDeletionOutcome.DELETED -> deleted += 1
            MediaSyncDeletionOutcome.NOT_PERMITTED -> deletionRefused = true
            else -> Unit
        }
        logger("mediaSync server delete name=$name outcome=${outcome.wireValue}")
        write(
            output,
            MediaSyncPacketType.DELETE_RESULT,
            JSONObject().put("name", name).put("outcome", outcome.wireValue),
        )
    }

    private fun write(output: OutputStream, type: MediaSyncPacketType, meta: JSONObject) {
        MediaSyncProtocol.write(output, MediaSyncPacket(type = type, meta = meta.toString()))
    }

    private fun reportSession(abortReason: String?) {
        val summary = MediaSyncServerSummary(served, bytes, deleted, deletionRefused, abortReason)
        served = 0
        bytes = 0L
        deleted = 0
        deletionRefused = false
        authenticated = false
        runCatching { onSessionFinished(summary) }
            .onFailure { logger("mediaSync server summary failed error=${it.message}") }
    }

    /** Serves an in-flight transfer no more; the caller tears the group down afterwards. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client?.close() }
        runCatching { server?.close() }
        server = null
        executor.shutdownNow()
    }

    companion object {
        /** Deliberately not the camera link's 38401 — the two data planes never share a port. */
        const val PORT = 38_403
        const val READ_TIMEOUT_MS = 45_000
        const val ABORT_CAMERA = "camera_active"
    }
}

/** Resolves the IPv4 address the Wi-Fi Direct group owner interface holds. */
internal fun groupOwnerAddress(interfaceName: String?): InetAddress? {
    if (interfaceName.isNullOrBlank()) return null
    return runCatching {
        java.net.NetworkInterface.getByName(interfaceName)
            ?.inetAddresses
            ?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
    }.getOrNull()
}
