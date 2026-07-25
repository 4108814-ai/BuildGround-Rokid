package com.anezium.rokidbus.shared

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

enum class MediaSyncPacketType(val id: Int) {
    /** phone -> glasses: session token, opens the data plane. */
    HELLO(1),

    /** glasses -> phone: token accepted. */
    HELLO_ACK(2),

    /** phone -> glasses: ask for the eligible capture list. */
    CATALOG_REQUEST(3),

    /** glasses -> phone: [MediaSyncCatalogContract] payload in `meta`. */
    CATALOG(4),

    /** phone -> glasses: `{"name": …}`, one file at a time. */
    FILE_REQUEST(5),

    /** glasses -> phone: `{"name","size","mtime"}` opening a transfer. */
    FILE_BEGIN(6),

    /** glasses -> phone: raw bytes in `payload`, `seq` counts from 0. */
    FILE_CHUNK(7),

    /**
     * glasses -> phone: transfer finished, `{"name","sha256"}`. The digest trails the bytes so
     * the glasses hash the file in the single pass they stream it.
     */
    FILE_END(8),

    /** phone -> glasses: `{"name","ok","delete"}` once the file is published (or failed). */
    FILE_ACK(9),

    /** glasses -> phone: `{"name","code"}` for a per-file failure; the session survives. */
    FILE_ERROR(10),

    /** glasses -> phone: `{"name","outcome"}` reporting the delete-after-sync attempt. */
    DELETE_RESULT(11),

    /** either side: `{"reason": …}`, the session ends. */
    ABORT(12),

    /** phone -> glasses: nothing left to do, close cleanly. */
    BYE(13),
    ;

    companion object {
        fun fromId(id: Int): MediaSyncPacketType =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown media sync packet type: $id")
    }
}

data class MediaSyncPacket(
    val type: MediaSyncPacketType,
    val seq: Int = 0,
    val meta: String = "",
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaSyncPacket) return false
        return type == other.type &&
            seq == other.seq &&
            meta == other.meta &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + seq
        result = 31 * result + meta.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Length-prefixed framing for the media-sync data plane. Same shape as
 * [CameraLinkProtocol] — deliberately a separate object so photo sync can never destabilise the
 * camera wire format.
 */
object MediaSyncProtocol {
    private const val MAGIC = 0x4d53594e // MSYN
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_META_BYTES = 256 * 1024
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_PAYLOAD_BYTES = 1024 * 1024

    /** Session tokens are compared with a constant-time helper; keep them fixed-width. */
    const val TOKEN_BYTES = 16

    fun write(output: OutputStream, packet: MediaSyncPacket) {
        val metaBytes = packet.meta.toByteArray(Charsets.UTF_8)
        require(metaBytes.size <= MAX_META_BYTES) { "Media sync metadata too large: ${metaBytes.size}" }
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) {
            "Media sync payload too large: ${packet.payload.size}"
        }
        output.write(
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put(VERSION.toByte())
                .put(packet.type.id.toByte())
                .putShort(0)
                .putInt(packet.seq)
                .putInt(metaBytes.size)
                .putInt(packet.payload.size)
                .array(),
        )
        if (metaBytes.isNotEmpty()) output.write(metaBytes)
        if (packet.payload.isNotEmpty()) output.write(packet.payload)
        output.flush()
    }

    /** Returns null only for a clean EOF before a new header begins. */
    fun read(input: InputStream): MediaSyncPacket? {
        val headerBytes = ByteArray(HEADER_BYTES)
        val headerRead = readFullyOrEof(input, headerBytes)
        if (headerRead == -1) return null
        if (headerRead != HEADER_BYTES) throw EOFException("Short media sync header")
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        require(header.int == MAGIC) { "Invalid media sync magic" }
        val version = header.get().toInt() and 0xff
        require(version == VERSION) { "Unsupported media sync version: $version" }
        val type = MediaSyncPacketType.fromId(header.get().toInt() and 0xff)
        header.short // reserved flags
        val seq = header.int
        val metaLength = header.int
        val payloadLength = header.int
        require(metaLength in 0..MAX_META_BYTES) { "Invalid media sync metadata length: $metaLength" }
        require(payloadLength in 0..MAX_PAYLOAD_BYTES) {
            "Invalid media sync payload length: $payloadLength"
        }
        return MediaSyncPacket(
            type = type,
            seq = seq,
            meta = String(readFully(input, metaLength, "metadata"), Charsets.UTF_8),
            payload = readFully(input, payloadLength, "payload"),
        )
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    /** Length-independent comparison so a wrong token cannot be probed byte by byte. */
    fun tokensMatch(expected: String, candidate: String): Boolean {
        if (expected.length != candidate.length) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].code xor candidate[index].code)
        }
        return difference == 0
    }

    private fun readFully(input: InputStream, size: Int, label: String): ByteArray {
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        if (readFullyOrEof(input, bytes) != size) throw EOFException("Short media sync $label")
        return bytes
    }

    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return if (offset == 0) -1 else offset
            if (count > 0) offset += count
        }
        return offset
    }
}
