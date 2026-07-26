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

/**
 * Pin size tier. Each tier carries its own text caps; `small` is the default and
 * keeps the v1 caps a payload without a `size` field has always had.
 */
enum class PinSurfaceSize(
    val wireValue: String,
    val maxTitleChars: Int,
    val maxLines: Int,
    val maxLineChars: Int,
) {
    SMALL("small", maxTitleChars = 24, maxLines = 2, maxLineChars = 28),
    MEDIUM("medium", maxTitleChars = 28, maxLines = 3, maxLineChars = 32),
    ;

    companion object {
        fun fromWireValue(value: String): PinSurfaceSize? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Per-line emphasis. [DEFAULT] has no wire value: a line that carries it is sent
 * as a plain string, exactly like every pre-emphasis payload.
 */
enum class PinSurfaceEmphasis(val wireValue: String?) {
    DEFAULT(null),
    BRIGHT("bright"),
    DIM("dim"),
    ;

    companion object {
        fun fromWireValue(value: String): PinSurfaceEmphasis? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class PinSurfaceLine(
    val text: String,
    val emphasis: PinSurfaceEmphasis = PinSurfaceEmphasis.DEFAULT,
)

data class PinSurfaceContent(
    val title: String?,
    val lines: List<PinSurfaceLine>,
    val position: PinSurfacePosition,
    val ttlMs: Long?,
    val size: PinSurfaceSize = PinSurfaceSize.SMALL,
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

    /** Small-tier caps under their historical names; see [PinSurfaceSize] for the per-tier values. */
    val MAX_TITLE_CHARS = PinSurfaceSize.SMALL.maxTitleChars
    val MAX_LINES = PinSurfaceSize.SMALL.maxLines
    val MAX_LINE_CHARS = PinSurfaceSize.SMALL.maxLineChars

    const val MIN_TTL_MS = 1_000L
    const val MAX_TTL_MS = 86_400_000L

    /**
     * Applied when a plugin sends no `ttlMs`. A pin outlives its owner's process, so
     * without a deadline a plugin killed before its hide would leave one on the glasses
     * until the hub restarts. Thirty minutes matches what pins are for — a fact worth
     * a corner for the length of an errand, not forever. Plugins that know better set
     * their own; nothing here stops a 24h pin.
     */
    const val DEFAULT_TTL_MS = 1_800_000L
    const val MIN_SHOW_INTERVAL_MS = 500L

    const val ERROR_INVALID_PIN = "INVALID_PIN"
    const val ERROR_PIN_RATE_LIMITED = "PIN_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun validateShow(payload: JSONObject): PinSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be pin")

        val size = when (val value = payload.opt("size")) {
            null -> PinSurfaceSize.SMALL
            is String -> PinSurfaceSize.fromWireValue(value) ?: return invalid("size is invalid")
            else -> return invalid("size must be a string")
        }

        val title = optionalTrimmedString(payload, "title")
            ?: if (payload.has("title")) return invalid("title must be a string") else null
        if (title != null && title.length > size.maxTitleChars) {
            return invalid("title exceeds ${size.maxTitleChars} characters")
        }

        val lines: List<PinSurfaceLine> = when (val value = payload.opt("lines")) {
            null -> emptyList()
            is JSONArray -> {
                if (value.length() > size.maxLines) return invalid("lines exceeds ${size.maxLines} entries")
                buildList<PinSurfaceLine> {
                    for (index in 0 until value.length()) {
                        when (val entry = value.opt(index)) {
                            is String -> add(PinSurfaceLine(entry.trim()))
                            is JSONObject -> {
                                val text = (entry.opt("text") as? String)?.trim()
                                    ?: return invalid("line text must be a string")
                                val emphasis = when (val raw = entry.opt("emphasis")) {
                                    null -> PinSurfaceEmphasis.DEFAULT
                                    is String -> PinSurfaceEmphasis.fromWireValue(raw)
                                        ?: return invalid("line emphasis is invalid")
                                    else -> return invalid("line emphasis must be a string")
                                }
                                add(PinSurfaceLine(text, emphasis))
                            }
                            else -> return invalid("lines must contain strings or objects")
                        }
                    }
                }
            }
            else -> return invalid("lines must be an array")
        }
        if (lines.any { it.text.length > size.maxLineChars }) {
            return invalid("line exceeds ${size.maxLineChars} characters")
        }

        if (title.isNullOrEmpty() && lines.none { it.text.isNotEmpty() }) {
            return invalid("title or lines must contain text")
        }

        val position = when (val value = payload.opt("position")) {
            null -> PinSurfacePosition.TOP_RIGHT
            is String -> PinSurfacePosition.fromWireValue(value)
                ?: return invalid("position is invalid")
            else -> return invalid("position must be a string")
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> DEFAULT_TTL_MS
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
                size = size,
            ),
        )
    }

    fun toPayload(surfaceId: String, content: PinSurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("lines", JSONArray().apply { content.lines.forEach { put(lineJsonValue(it)) } })
        .put("position", content.position.wireValue)
        .apply {
            content.title?.let { put("title", it) }
            // Omitted for the default tier so pre-size payloads stay byte-identical.
            if (content.size != PinSurfaceSize.SMALL) put("size", content.size.wireValue)
            content.ttlMs?.let { put("ttlMs", it.coerceIn(MIN_TTL_MS, MAX_TTL_MS)) }
        }

    /** Plain string when nothing but text is set, object otherwise (same shape as card lines). */
    private fun lineJsonValue(line: PinSurfaceLine): Any {
        val emphasis = line.emphasis.wireValue ?: return line.text
        return JSONObject().put("text", line.text).put("emphasis", emphasis)
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
