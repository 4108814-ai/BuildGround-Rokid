package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Photo sync's data plane, expressed as ordinary bus envelopes.
 *
 * v1 moved bytes over a dedicated Wi-Fi Direct group. Three separate ROM landmines later — see the
 * P2P findings section in BUSSPEC — that transport was scrapped for the link that is already up
 * whenever the glasses are connected: the Bluetooth bus itself. Chunks ride in
 * [BusEnvelope.binary], which the SPP frame layer already carries for the HUD image channel, so
 * there is no new binary mechanism here at all.
 *
 * The trade is deliberate: ~0.36 MB/s instead of ~9 MB/s, in exchange for a transport that never
 * has to be negotiated, never needs Wi-Fi, and cannot fail in three different ways before the
 * first byte moves. Photo sync is a passive, charge-anchored background feature; it can be slow,
 * but it must not be fragile, and it must never crowd out whatever else needs the link.
 */
object MediaSyncTransferContract {
    const val VERSION = 1

    /**
     * 32 KiB per chunk.
     *
     * The HUD image channel is measured at ~64 KiB per ~180 ms on this hardware. Halving that puts
     * a chunk on the wire in ~90 ms, which halves how long any other bus message can be stuck
     * behind ours — the whole point of the politeness layer — while costing about 0.4% in header
     * overhead (~130 bytes of envelope JSON per chunk). Cheap insurance for a link everything else
     * shares.
     */
    const val CHUNK_BYTES = 32 * 1024

    /** Well clear of the 2 MiB SPP frame ceiling once envelope and header are added. */
    const val MAX_CHUNK_BYTES = 64 * 1024

    fun sessionJson(sessionId: String): JSONObject =
        JSONObject().put("version", VERSION).put("sessionId", sessionId)

    fun fileRequest(sessionId: String, name: String, offset: Long): JSONObject =
        sessionJson(sessionId).put("name", name).put("offset", offset)

    fun fileBegin(sessionId: String, name: String, size: Long, modifiedMillis: Long, offset: Long): JSONObject =
        sessionJson(sessionId)
            .put("name", name)
            .put("size", size)
            .put("mtime", modifiedMillis)
            .put("offset", offset)

    fun chunkMeta(sessionId: String, name: String, seq: Int, offset: Long): JSONObject =
        sessionJson(sessionId).put("name", name).put("seq", seq).put("offset", offset)

    fun fileEnd(sessionId: String, name: String, sha256: String): JSONObject =
        sessionJson(sessionId).put("name", name).put("sha256", sha256)

    fun fileAck(sessionId: String, name: String, ok: Boolean, delete: Boolean): JSONObject =
        sessionJson(sessionId).put("name", name).put("ok", ok).put("delete", delete)

    fun fileError(sessionId: String, name: String, code: String): JSONObject =
        sessionJson(sessionId).put("name", name).put("code", code)

    fun deleteResult(sessionId: String, name: String, outcome: String): JSONObject =
        sessionJson(sessionId).put("name", name).put("outcome", outcome)

    fun abort(sessionId: String, reason: String): JSONObject =
        sessionJson(sessionId).put("reason", reason)

    /** Every data-plane message carries the session id; a stale one must never be acted on. */
    fun isForSession(payload: JSONObject, sessionId: String): Boolean =
        payload.optInt("version") == VERSION &&
            payload.optString("sessionId") == sessionId &&
            sessionId.isNotBlank()

    fun name(payload: JSONObject): String? = payload.optString("name").takeIf(String::isNotBlank)

    fun offset(payload: JSONObject): Long = payload.optLong("offset", 0L).coerceAtLeast(0L)

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    const val ERROR_NOT_FOUND = "not_found"
    const val ERROR_READ_FAILED = "read_failed"
    const val ERROR_CHANGED = "changed"
    const val ABORT_CAMERA = "camera_active"
    const val ABORT_LINK = "link_down"
}
