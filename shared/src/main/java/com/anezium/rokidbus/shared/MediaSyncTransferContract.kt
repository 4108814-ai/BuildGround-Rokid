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

    /**
     * How far ahead of the receiver's acknowledged offset the sender may run: 128 KiB, four
     * chunks.
     *
     * This exists because `SppServerManager.send` returns once the frame is handed to the socket,
     * not once it is on the air — measured on device as a ~41 ms enqueue cadence against a ~90 ms
     * wire time per chunk, which let the kernel queue grow several chunks deep. Two things broke
     * as a result: the terminator overtook its own data, and the politeness layer was pacing
     * enqueues while the radio kept transmitting a backlog for seconds after a yield or an abort.
     *
     * A bounded window fixes both. At the measured ~0.36 MB/s it is ~360 ms of buffered air time,
     * which is short enough that a camera session stops real transmission almost immediately, and
     * long enough that an ack round trip on the control channel never starves the pipe.
     */
    const val ACK_WINDOW_BYTES = 128 * 1024

    /** Half a window, so an ack is always in flight before the sender can reach the ceiling. */
    const val ACK_EVERY_CHUNKS = 2

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

    /** Receiver -> sender: every byte up to [staged] is durably written. */
    fun fileProgress(sessionId: String, name: String, staged: Long): JSONObject =
        sessionJson(sessionId).put("name", name).put("staged", staged)

    fun staged(payload: JSONObject): Long = payload.optLong("staged", 0L).coerceAtLeast(0L)

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

    /**
     * Ordering guarantees per message type, since the control channel (CXR, small JSON) and the
     * data channel (SPP, binary) have independent latencies and a message on one can overtake a
     * message on the other:
     *
     * - `FILE_CHUNK` — SPP, and the only ordered stream. Carries an absolute offset; the receiver
     *   refuses anything that is not exactly where it expects, so a gap can never be papered over.
     * - `FILE_BEGIN` — may arrive early on the control channel. Harmless: it is sent before any
     *   chunk of that file exists, so early is the only thing it can be.
     * - `FILE_END` — must NOT be allowed to overtake the tail, which is exactly what happened on
     *   device. It is therefore sent only once the receiver has acknowledged every byte, so
     *   `staged == expected` holds before the terminator is even written. The staged/expected log
     *   line at verification is now an invariant check rather than a diagnostic.
     * - `FILE_PROGRESS` — receiver to sender, and monotonic; a late or duplicated ack can only
     *   ever repeat an offset already passed, never retract one.
     * - `ABORT` / `FILE_ERROR` — terminal, and overtaking chunks is fine: the receiver stops the
     *   file either way and the staged partial is kept for resume.
     */
    const val ERROR_NOT_FOUND = "not_found"
    const val ERROR_READ_FAILED = "read_failed"
    const val ERROR_CHANGED = "changed"
    const val ABORT_CAMERA = "camera_active"
    const val ABORT_LINK = "link_down"
}
