package com.anezium.rokidbus.shared

import org.json.JSONObject

enum class RemotePointerAction(val wireValue: String) {
    SHOW("show"),
    MOVE("move"),
    MOVE_END("move_end"),
    CLICK("click"),
    LONG_PRESS("long_press"),
    HIDE("hide"),
    ;

    companion object {
        fun fromWireValue(value: String): RemotePointerAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RemotePointerErrorCode(val wireValue: String) {
    SERVICE_UNAVAILABLE("service_unavailable"),
    ACTION_UNAVAILABLE("action_unavailable"),
    GESTURE_CANCELLED("gesture_cancelled"),
    STALE_SEQUENCE("stale_sequence"),
    STREAM_RETIRED("stream_retired"),
    STREAM_NOT_STARTED("stream_not_started"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromWireValue(value: String): RemotePointerErrorCode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class RemotePointerCommand(
    val streamId: String,
    val sequence: Long,
    val action: RemotePointerAction,
    /** Absolute fraction of the glasses display width. Absent only for [RemotePointerAction.HIDE]. */
    val x: Double? = null,
    /** Absolute fraction of the glasses display height. Absent only for [RemotePointerAction.HIDE]. */
    val y: Double? = null,
)

data class RemotePointerResult(
    val streamId: String,
    val sequence: Long,
    val action: RemotePointerAction,
    val errorCode: RemotePointerErrorCode? = null,
) {
    val success: Boolean get() = errorCode == null
}

/**
 * Trusted hub-to-hub pointer control. A random stream id and monotonically increasing sequence
 * suppress transport replays. Coordinates are absolute and normalized, so a delayed move is
 * harmless and a click carries the exact position it must activate even across transport changes.
 */
object RemotePointerContract {
    const val VERSION = 1
    const val COMMAND_PATH = "/core/pointer/command"
    const val RESULT_PATH = "/core/pointer/result"
    const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
    const val MAX_STREAM_ID_LENGTH = 128
    const val MAX_MESSAGE_CHARS = 1024
    const val MAX_MESSAGE_BYTES = 1024

    private const val TYPE_COMMAND = "pointer_command"
    private const val TYPE_RESULT = "pointer_result"
    private val streamIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{15,127}")

    fun command(value: RemotePointerCommand): JSONObject {
        require(isValidStreamId(value.streamId)) { "Invalid pointer stream id" }
        require(isValidSequence(value.sequence)) { "Invalid pointer sequence" }
        require(hasValidActionShape(value)) { "Invalid pointer action payload" }
        return base(TYPE_COMMAND, value.streamId, value.sequence)
            .put("action", value.action.wireValue)
            .apply {
                if (value.action != RemotePointerAction.HIDE) {
                    put("x", value.x)
                    put("y", value.y)
                }
            }
    }

    fun parseCommand(payload: JSONObject): RemotePointerCommand? {
        if (!hasValidEnvelope(payload, TYPE_COMMAND)) return null
        val action = RemotePointerAction.fromWireValue(
            payload.strictString("action") ?: return null,
        ) ?: return null
        val command = if (action == RemotePointerAction.HIDE) {
            if (payload.has("x") || payload.has("y")) return null
            RemotePointerCommand(
                streamId = payload.getString("streamId"),
                sequence = payload.getLong("sequence"),
                action = action,
            )
        } else {
            RemotePointerCommand(
                streamId = payload.getString("streamId"),
                sequence = payload.getLong("sequence"),
                action = action,
                x = payload.strictDouble("x") ?: return null,
                y = payload.strictDouble("y") ?: return null,
            )
        }
        return command.takeIf(::hasValidActionShape)
    }

    fun result(value: RemotePointerResult): JSONObject {
        require(isValidStreamId(value.streamId)) { "Invalid pointer stream id" }
        require(isValidSequence(value.sequence)) { "Invalid pointer sequence" }
        return base(TYPE_RESULT, value.streamId, value.sequence)
            .put("action", value.action.wireValue)
            .put("success", value.success)
            .putOpt("errorCode", value.errorCode?.wireValue)
    }

    fun parseResult(payload: JSONObject): RemotePointerResult? {
        if (!hasValidEnvelope(payload, TYPE_RESULT)) return null
        val action = RemotePointerAction.fromWireValue(
            payload.strictString("action") ?: return null,
        ) ?: return null
        val success = payload.strictBoolean("success") ?: return null
        val error = if (payload.has("errorCode")) {
            RemotePointerErrorCode.fromWireValue(
                payload.strictString("errorCode") ?: return null,
            ) ?: return null
        } else {
            null
        }
        if (success != (error == null)) return null
        return RemotePointerResult(
            streamId = payload.getString("streamId"),
            sequence = payload.getLong("sequence"),
            action = action,
            errorCode = error,
        )
    }

    fun isValidStreamId(value: String): Boolean =
        value.length <= MAX_STREAM_ID_LENGTH && streamIdPattern.matches(value)

    fun isValidSequence(value: Long): Boolean = value in 1..MAX_SAFE_SEQUENCE

    private fun hasValidActionShape(value: RemotePointerCommand): Boolean =
        if (value.action == RemotePointerAction.HIDE) {
            value.x == null && value.y == null
        } else {
            value.x?.let(::isValidCoordinate) == true &&
                value.y?.let(::isValidCoordinate) == true
        }

    private fun isValidCoordinate(value: Double): Boolean = value.isFinite() && value in 0.0..1.0

    private fun hasValidEnvelope(payload: JSONObject, type: String): Boolean {
        val encoded = payload.toString()
        return encoded.length <= MAX_MESSAGE_CHARS &&
            encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES &&
            payload.strictInt("version") == VERSION &&
            payload.strictString("type") == type &&
            payload.strictString("streamId")?.let(::isValidStreamId) == true &&
            payload.strictLong("sequence")?.let(::isValidSequence) == true
    }

    private fun base(type: String, streamId: String, sequence: Long): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("streamId", streamId)
        .put("sequence", sequence)

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

    private fun JSONObject.strictDouble(key: String): Double? = when (val value = opt(key)) {
        is Byte -> value.toDouble()
        is Short -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Float -> value.toDouble().takeIf(Double::isFinite)
        is Double -> value.takeIf(Double::isFinite)
        is Number -> value.toDouble().takeIf(Double::isFinite)
        else -> null
    }

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}
