package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Why a notice stopped being visible. Delivered to its owner exactly once.
 */
enum class NoticeCloseReason(val wireValue: String) {
    /** The wearer pressed BACK. */
    USER("user"),

    /** The TTL or the absolute lifetime ran out. */
    TIMEOUT("timeout"),

    /** The owner called hide. */
    OWNER("owner"),

    /** Another plugin took the slot. */
    REPLACED("replaced"),

    /** The owner lost the bus. Best-effort: not delivered if the owner is what vanished. */
    DISCONNECT("disconnect"),
    ;

    companion object {
        fun fromWireValue(value: String): NoticeCloseReason? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class NoticeSurfaceContent(
    val title: String?,
    val body: String?,
    val footer: String?,
    val interactive: Boolean = false,
    val ttlMs: Long = NoticeSurfaceContract.DEFAULT_TTL_MS,
)

/**
 * One field of an update. Absent from the payload is not the same as sent empty:
 * absent keeps the current value, present-and-empty clears it. A nullable field
 * cannot express both, so presence is carried by the wrapper and the cleared
 * value by its contents.
 */
@JvmInline
value class NoticeField<T>(val value: T)

data class NoticeSurfacePatch(
    val title: NoticeField<String?>? = null,
    val body: NoticeField<String?>? = null,
    val footer: NoticeField<String?>? = null,
    val interactive: NoticeField<Boolean>? = null,
    val ttlMs: NoticeField<Long>? = null,
) {
    // Presence is the test, never the value: `?:` here would treat a field sent
    // empty as a field left out, and clearing a footer would silently keep it.
    fun applyTo(content: NoticeSurfaceContent): NoticeSurfaceContent = content.copy(
        title = if (title != null) title.value else content.title,
        body = if (body != null) body.value else content.body,
        footer = if (footer != null) footer.value else content.footer,
        interactive = if (interactive != null) interactive.value else content.interactive,
        ttlMs = if (ttlMs != null) ttlMs.value else content.ttlMs,
    )
}

sealed interface NoticeSurfaceValidationResult {
    data class Valid(val content: NoticeSurfaceContent) : NoticeSurfaceValidationResult
    data class Invalid(val reason: String) : NoticeSurfaceValidationResult
}

sealed interface NoticeSurfacePatchResult {
    data class Valid(val patch: NoticeSurfacePatch) : NoticeSurfacePatchResult
    data class Invalid(val reason: String) : NoticeSurfacePatchResult
}

/** Pure notice-surface v1 validation and normalization with no Android dependencies. */
object NoticeSurfaceContract {
    const val KIND = "notice"
    const val VERSION = 1
    const val LOCAL_SURFACE_ID = "notice"

    const val MAX_TITLE_CHARS = 32
    const val MAX_BODY_CHARS = 240
    const val MAX_FOOTER_CHARS = 40

    const val DEFAULT_TTL_MS = 8_000L
    const val MIN_TTL_MS = 2_000L
    const val MAX_TTL_MS = 20_000L

    /**
     * Hard ceiling from the first accepted show, enforced by the phone hub. The TTL
     * restarts on every update, so without this a plugin could keep a banner in the
     * wearer's eye forever by updating it — which is precisely what a notice must
     * not be. An ongoing thing is an activity; see plan 012.
     */
    const val MAX_LIFETIME_MS = 60_000L

    /**
     * Accepted messages per second per plugin, shared between show and update.
     * Sized so a transcript can refresh the body a few times a second without a
     * plugin being able to drive the renderer.
     */
    const val MAX_MESSAGES_PER_SECOND = 5

    const val ERROR_INVALID_NOTICE = "INVALID_NOTICE"
    const val ERROR_NOTICE_RATE_LIMITED = "NOTICE_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun validateShow(payload: JSONObject): NoticeSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be notice")

        val title = when (val result = readText(payload, "title", MAX_TITLE_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        val body = when (val result = readText(payload, "body", MAX_BODY_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        val footer = when (val result = readText(payload, "footer", MAX_FOOTER_CHARS)) {
            is TextResult.Invalid -> return invalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> result.value
        }
        if (title.isNullOrEmpty() && body.isNullOrEmpty()) {
            return invalid("title or body must contain text")
        }

        val interactive = when (val value = payload.opt("interactive")) {
            null -> false
            is Boolean -> value
            else -> return invalid("interactive must be a boolean")
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> DEFAULT_TTL_MS
            is Number -> integerLong(value)?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                ?: return invalid("ttlMs must be an integer")
            else -> return invalid("ttlMs must be an integer")
        }

        return NoticeSurfaceValidationResult.Valid(
            NoticeSurfaceContent(
                title = title?.takeIf { it.isNotEmpty() },
                body = body?.takeIf { it.isNotEmpty() },
                footer = footer?.takeIf { it.isNotEmpty() },
                interactive = interactive,
                ttlMs = ttlMs,
            ),
        )
    }

    /**
     * An update carries only what changed. Unlike a show it is not required to
     * leave the notice with any text: the caps still apply, but "did the wearer
     * end up with an empty banner" is checked after the patch is applied, by the
     * caller that owns the current content.
     */
    fun validateUpdate(payload: JSONObject): NoticeSurfacePatchResult {
        if (payload.has("kind") && payload.opt("kind") != KIND) {
            return patchInvalid("kind must be notice")
        }

        val title = when (val result = readText(payload, "title", MAX_TITLE_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }
        val body = when (val result = readText(payload, "body", MAX_BODY_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }
        val footer = when (val result = readText(payload, "footer", MAX_FOOTER_CHARS)) {
            is TextResult.Invalid -> return patchInvalid(result.reason)
            is TextResult.Absent -> null
            is TextResult.Present -> NoticeField(result.value?.takeIf { it.isNotEmpty() })
        }

        val interactive = when (val value = payload.opt("interactive")) {
            null -> null
            is Boolean -> NoticeField(value)
            else -> return patchInvalid("interactive must be a boolean")
        }

        val ttlMs = when (val value = payload.opt("ttlMs")) {
            null -> null
            is Number -> NoticeField(
                integerLong(value)?.coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                    ?: return patchInvalid("ttlMs must be an integer"),
            )
            else -> return patchInvalid("ttlMs must be an integer")
        }

        return NoticeSurfacePatchResult.Valid(
            NoticeSurfacePatch(
                title = title,
                body = body,
                footer = footer,
                interactive = interactive,
                ttlMs = ttlMs,
            ),
        )
    }

    fun toPayload(surfaceId: String, content: NoticeSurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("ttlMs", content.ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS))
        .apply {
            content.title?.let { put("title", it) }
            content.body?.let { put("body", it) }
            content.footer?.let { put("footer", it) }
            // Omitted when false so a non-interactive payload stays minimal.
            if (content.interactive) put("interactive", true)
        }

    fun closedPayload(surfaceId: String, reason: NoticeCloseReason): JSONObject = JSONObject()
        .put("noticeId", surfaceId)
        .put("reason", reason.wireValue)

    private sealed interface TextResult {
        data object Absent : TextResult
        data class Present(val value: String?) : TextResult
        data class Invalid(val reason: String) : TextResult
    }

    private fun readText(payload: JSONObject, key: String, maxChars: Int): TextResult {
        if (!payload.has(key)) return TextResult.Absent
        val raw = payload.opt(key)
        if (raw == JSONObject.NULL) return TextResult.Present(null)
        val text = raw as? String ?: return TextResult.Invalid("$key must be a string")
        // Newlines collapse to spaces in v1: the renderer owns wrapping, and a
        // plugin cannot be allowed to lay the banner out by hand.
        val normalized = text.replace(NEWLINES, " ").trim()
        if (normalized.length > maxChars) return TextResult.Invalid("$key exceeds $maxChars characters")
        return TextResult.Present(normalized)
    }

    private fun integerLong(number: Number): Long? {
        val double = number.toDouble()
        val long = number.toLong()
        return long.takeIf { double.isFinite() && double == long.toDouble() }
    }

    private fun invalid(reason: String) = NoticeSurfaceValidationResult.Invalid(reason)

    private fun patchInvalid(reason: String) = NoticeSurfacePatchResult.Invalid(reason)

    private val NEWLINES = Regex("[\\r\\n]+")
}
