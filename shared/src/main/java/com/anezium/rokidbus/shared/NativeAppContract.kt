package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

data class NativeAppEntry(
    val packageName: String,
    val label: String,
    val versionCode: Long? = null,
)

enum class NativeAppErrorCode(val wireValue: String) {
    NOT_FOUND("not_found"),
    NOT_LAUNCHABLE("not_launchable"),
    NOT_ALLOWED("not_allowed"),
    INVALID_REQUEST("invalid_request"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromWireValue(value: String): NativeAppErrorCode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class NativeAppListResult(
    val requestId: String,
    val apps: List<NativeAppEntry>,
    val errorCode: NativeAppErrorCode? = null,
) {
    val success: Boolean get() = errorCode == null
}

data class NativeAppLaunchRequest(
    val requestId: String,
    val packageName: String,
)

data class NativeAppLaunchResult(
    val requestId: String,
    val packageName: String,
    val errorCode: NativeAppErrorCode? = null,
) {
    val success: Boolean get() = errorCode == null
}

/** Wire-only contract; package discovery and launching remain glasses platform responsibilities. */
object NativeAppContract {
    const val VERSION = 1
    const val REQUEST_PATH = "/core/native-apps/request"
    const val RESULT_PATH = "/core/native-apps/result"

    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_PACKAGE_NAME_LENGTH = 255
    const val MAX_LABEL_LENGTH = 96
    const val MAX_APPS = 64
    const val MAX_MESSAGE_CHARS = 16 * 1024
    const val MAX_MESSAGE_BYTES = 16 * 1024
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private const val TYPE_LIST_REQUEST = "list_request"
    private const val TYPE_LIST_RESULT = "list_result"
    private const val TYPE_LAUNCH_REQUEST = "launch_request"
    private const val TYPE_LAUNCH_RESULT = "launch_result"

    private val requestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")
    private val packageNamePattern = Regex(
        "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+",
    )

    fun listRequest(requestId: String): JSONObject {
        require(isValidRequestId(requestId)) { "Invalid native-app request id" }
        return base(TYPE_LIST_REQUEST, requestId)
    }

    fun parseListRequest(payload: JSONObject): String? =
        if (hasValidEnvelope(payload, TYPE_LIST_REQUEST)) payload.getString("requestId") else null

    fun listResult(value: NativeAppListResult): JSONObject {
        require(isValidRequestId(value.requestId)) { "Invalid native-app request id" }
        require(value.apps.size <= MAX_APPS) { "Native-app catalog too large" }
        require(value.errorCode == null || value.apps.isEmpty()) { "Failed catalog must be empty" }
        val seen = HashSet<String>(value.apps.size)
        val apps = JSONArray().apply {
            value.apps.forEach { app ->
                require(isValidEntry(app)) { "Invalid native-app entry" }
                require(seen.add(app.packageName)) { "Duplicate native-app package" }
                put(
                    JSONObject()
                        .put("packageName", app.packageName)
                        .put("label", app.label)
                        .putOpt("versionCode", app.versionCode),
                )
            }
        }
        return base(TYPE_LIST_RESULT, value.requestId)
            .put("success", value.success)
            .put("apps", apps)
            .putOpt("errorCode", value.errorCode?.wireValue)
            .also {
                require(isWithinMessageLimit(it)) { "Native-app result too large" }
            }
    }

    fun parseListResult(payload: JSONObject): NativeAppListResult? {
        if (!hasValidEnvelope(payload, TYPE_LIST_RESULT)) return null
        val success = payload.strictBoolean("success") ?: return null
        val array = payload.opt("apps") as? JSONArray ?: return null
        if (array.length() > MAX_APPS) return null
        val apps = ArrayList<NativeAppEntry>(array.length())
        val seen = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val json = array.opt(index) as? JSONObject ?: return null
            val packageName = json.strictString("packageName") ?: return null
            val label = json.strictString("label") ?: return null
            val versionCode = if (json.has("versionCode")) {
                json.strictLong("versionCode")?.takeIf { it in 0..MAX_SAFE_INTEGER } ?: return null
            } else {
                null
            }
            val entry = NativeAppEntry(packageName, label, versionCode)
            if (!isValidEntry(entry) || !seen.add(packageName)) return null
            apps += entry
        }
        val error = if (payload.has("errorCode")) {
            NativeAppErrorCode.fromWireValue(payload.strictString("errorCode") ?: return null)
                ?: return null
        } else {
            null
        }
        if (success != (error == null) || error != null && apps.isNotEmpty()) return null
        return NativeAppListResult(payload.getString("requestId"), apps, error)
    }

    fun launchRequest(value: NativeAppLaunchRequest): JSONObject {
        require(isValidRequestId(value.requestId)) { "Invalid native-app request id" }
        require(isValidPackageName(value.packageName)) { "Invalid package name" }
        return base(TYPE_LAUNCH_REQUEST, value.requestId)
            .put("packageName", value.packageName)
    }

    fun parseLaunchRequest(payload: JSONObject): NativeAppLaunchRequest? {
        if (!hasValidEnvelope(payload, TYPE_LAUNCH_REQUEST)) return null
        val packageName = payload.strictString("packageName")
            ?.takeIf(::isValidPackageName) ?: return null
        return NativeAppLaunchRequest(payload.getString("requestId"), packageName)
    }

    fun launchResult(value: NativeAppLaunchResult): JSONObject {
        require(isValidRequestId(value.requestId)) { "Invalid native-app request id" }
        require(isValidPackageName(value.packageName)) { "Invalid package name" }
        return base(TYPE_LAUNCH_RESULT, value.requestId)
            .put("packageName", value.packageName)
            .put("success", value.success)
            .putOpt("errorCode", value.errorCode?.wireValue)
    }

    fun parseLaunchResult(payload: JSONObject): NativeAppLaunchResult? {
        if (!hasValidEnvelope(payload, TYPE_LAUNCH_RESULT)) return null
        val packageName = payload.strictString("packageName")
            ?.takeIf(::isValidPackageName) ?: return null
        val success = payload.strictBoolean("success") ?: return null
        val error = if (payload.has("errorCode")) {
            NativeAppErrorCode.fromWireValue(payload.strictString("errorCode") ?: return null)
                ?: return null
        } else {
            null
        }
        if (success != (error == null)) return null
        return NativeAppLaunchResult(payload.getString("requestId"), packageName, error)
    }

    fun isValidRequestId(value: String): Boolean =
        value.length <= MAX_REQUEST_ID_LENGTH && requestIdPattern.matches(value)

    fun isValidPackageName(value: String): Boolean =
        value.length <= MAX_PACKAGE_NAME_LENGTH && packageNamePattern.matches(value)

    private fun isValidEntry(value: NativeAppEntry): Boolean =
        isValidPackageName(value.packageName) &&
            isValidLabel(value.label) &&
            (value.versionCode == null || value.versionCode in 0..MAX_SAFE_INTEGER)

    private fun isValidLabel(value: String): Boolean =
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_LABEL_LENGTH &&
            value.none(Char::isISOControl) &&
            hasValidSurrogates(value)

    private fun hasValidEnvelope(payload: JSONObject, type: String): Boolean =
        isWithinMessageLimit(payload) &&
            payload.strictInt("version") == VERSION &&
            payload.strictString("type") == type &&
            payload.strictString("requestId")?.let(::isValidRequestId) == true

    private fun base(type: String, requestId: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("requestId", requestId)

    private fun isWithinMessageLimit(payload: JSONObject): Boolean {
        val encoded = payload.toString()
        return encoded.length <= MAX_MESSAGE_CHARS &&
            encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES
    }

    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }

                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun JSONObject.strictString(key: String): String? = opt(key) as? String

    private fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

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
