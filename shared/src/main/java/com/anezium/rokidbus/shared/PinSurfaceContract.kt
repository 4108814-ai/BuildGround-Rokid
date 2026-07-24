package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

enum class PinSurfacePosition(val wireValue: String) {
    TOP_LEFT("top-left"),
    TOP_RIGHT("top-right"),
    BOTTOM_LEFT("bottom-left"),
    BOTTOM_RIGHT("bottom-right"),
    ;

    companion object {
        fun fromWireValue(value: String): PinSurfacePosition? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class PinSurfaceContent(
    val title: String?,
    val lines: List<String>,
    val position: PinSurfacePosition,
    val ttlMs: Long?,
)

sealed interface PinSurfaceValidationResult {
    data class Valid(val content: PinSurfaceContent) : PinSurfaceValidationResult
    data class Invalid(val reason: String) : PinSurfaceValidationResult
}

/** Pure pin-surface v1 validation and normalization with no Android dependencies. */
object PinSurfaceContract {
    const val KIND = "pin"
    const val VERSION = 1
    const val LOCAL_SURFACE_ID = "pin"
    const val MAX_TITLE_CHARS = 24
    const val MAX_LINES = 2
    const val MAX_LINE_CHARS = 28
    const val MIN_TTL_MS = 1_000L
    const val MAX_TTL_MS = 86_400_000L
    const val MIN_SHOW_INTERVAL_MS = 500L

    const val ERROR_INVALID_PIN = "INVALID_PIN"
    const val ERROR_PIN_RATE_LIMITED = "PIN_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun validateShow(payload: JSONObject): PinSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be pin")

        val title = optionalTrimmedString(payload, "title")
            ?: if (payload.has("title")) return invalid("title must be a string") else null
        if (title != null && title.length > MAX_TITLE_CHARS) {
            return invalid("title exceeds $MAX_TITLE_CHARS characters")
        }

        val lines: List<String> = when (val value = payload.opt("lines")) {
            null -> emptyList()
            is JSONArray -> {
                if (value.length() > MAX_LINES) return invalid("lines exceeds $MAX_LINES entries")
                buildList<String> {
                    for (index in 0 until value.length()) {
                        val line = value.opt(index) as? String
                            ?: return invalid("lines must contain only strings")
                        val trimmed = line.trim()
                        if (trimmed.length > MAX_LINE_CHARS) {
                            return invalid("line exceeds $MAX_LINE_CHARS characters")
                        }
                        add(trimmed)
                    }
                }
            }
            else -> return invalid("lines must be an array")
        }

        if (title.isNullOrEmpty() && lines.none { it.isNotEmpty() }) {
            return invalid("title or lines must contain text")
        }

        val position = when (val value = payload.opt("position")) {
            null -> PinSurfacePosition.TOP_RIGHT
            is String -> PinSurfacePosition.fromWireValue(value)
                ?: return invalid("position is invalid")
            else -> return invalid("position must be a string")
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> null
            is Number -> integerLong(value)?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                ?: return invalid("ttlMs must be an integer")
            else -> return invalid("ttlMs must be an integer")
        }

        return PinSurfaceValidationResult.Valid(
            PinSurfaceContent(
                title = title?.takeIf { it.isNotEmpty() },
                lines = lines,
                position = position,
                ttlMs = ttlMs,
            ),
        )
    }

    fun toPayload(surfaceId: String, content: PinSurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("lines", JSONArray(content.lines))
        .put("position", content.position.wireValue)
        .apply {
            content.title?.let { put("title", it) }
            content.ttlMs?.let { put("ttlMs", it.coerceIn(MIN_TTL_MS, MAX_TTL_MS)) }
        }

    private fun optionalTrimmedString(payload: JSONObject, key: String): String? {
        if (!payload.has(key)) return null
        return (payload.opt(key) as? String)?.trim()
    }

    private fun integerLong(number: Number): Long? {
        val double = number.toDouble()
        val long = number.toLong()
        return long.takeIf { double.isFinite() && double == long.toDouble() }
    }

    private fun invalid(reason: String) = PinSurfaceValidationResult.Invalid(reason)
}
