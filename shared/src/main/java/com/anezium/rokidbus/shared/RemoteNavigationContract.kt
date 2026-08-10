package com.anezium.rokidbus.shared

import org.json.JSONObject

enum class RemoteNavigationAction(val wireValue: String) {
    PREVIOUS("previous"),
    NEXT("next"),
    SELECT("select"),
    BACK("back"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteNavigationAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RemoteNavigationErrorCode(val wireValue: String) {
    SERVICE_UNAVAILABLE("service_unavailable"),
    ACTION_UNAVAILABLE("action_unavailable"),
    INVALID_REQUEST("invalid_request"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromWireValue(value: String): RemoteNavigationErrorCode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class RemoteNavigationRequest(
    val requestId: String,
    val action: RemoteNavigationAction,
)

data class RemoteNavigationResult(
    val requestId: String,
    val action: RemoteNavigationAction,
    val errorCode: RemoteNavigationErrorCode? = null,
) {
    val success: Boolean get() = errorCode == null
}

/**
 * System-wide navigation is deliberately independent of an active editor session. Receivers use
 * requestId for bounded replay suppression so transport retries cannot perform an action twice.
 */
object RemoteNavigationContract {
    const val VERSION = 1
    const val REQUEST_PATH = "/core/navigation/request"
    const val RESULT_PATH = "/core/navigation/result"
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_MESSAGE_CHARS = 1024
    const val MAX_MESSAGE_BYTES = 1024

    private const val TYPE_REQUEST = "navigation_request"
    private const val TYPE_RESULT = "navigation_result"
    private val requestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{15,127}")

    fun request(value: RemoteNavigationRequest): JSONObject {
        require(isValidRequestId(value.requestId)) { "Invalid navigation request id" }
        return base(TYPE_REQUEST, value.requestId)
            .put("action", value.action.wireValue)
    }

    fun parseRequest(payload: JSONObject): RemoteNavigationRequest? {
        if (!hasValidEnvelope(payload, TYPE_REQUEST)) return null
        val action = RemoteNavigationAction.fromWireValue(
            payload.strictString("action") ?: return null,
        ) ?: return null
        return RemoteNavigationRequest(payload.getString("requestId"), action)
    }

    fun result(value: RemoteNavigationResult): JSONObject {
        require(isValidRequestId(value.requestId)) { "Invalid navigation request id" }
        return base(TYPE_RESULT, value.requestId)
            .put("action", value.action.wireValue)
            .put("success", value.success)
            .putOpt("errorCode", value.errorCode?.wireValue)
    }

    fun parseResult(payload: JSONObject): RemoteNavigationResult? {
        if (!hasValidEnvelope(payload, TYPE_RESULT)) return null
        val action = RemoteNavigationAction.fromWireValue(
            payload.strictString("action") ?: return null,
        ) ?: return null
        val success = payload.strictBoolean("success") ?: return null
        val error = if (payload.has("errorCode")) {
            RemoteNavigationErrorCode.fromWireValue(
                payload.strictString("errorCode") ?: return null,
            ) ?: return null
        } else {
            null
        }
        if (success != (error == null)) return null
        return RemoteNavigationResult(payload.getString("requestId"), action, error)
    }

    fun isValidRequestId(value: String): Boolean =
        value.length <= MAX_REQUEST_ID_LENGTH && requestIdPattern.matches(value)

    private fun hasValidEnvelope(payload: JSONObject, type: String): Boolean {
        val encoded = payload.toString()
        return encoded.length <= MAX_MESSAGE_CHARS &&
            encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES &&
            payload.strictInt("version") == VERSION &&
            payload.strictString("type") == type &&
            payload.strictString("requestId")?.let(::isValidRequestId) == true
    }

    private fun base(type: String, requestId: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("requestId", requestId)

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }
}
