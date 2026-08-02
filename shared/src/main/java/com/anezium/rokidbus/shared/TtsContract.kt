package com.anezium.rokidbus.shared

import org.json.JSONObject

enum class TtsDoneReason {
    COMPLETED,
    STOPPED,
    PREEMPTED,
    CANCELLED,
    UNAVAILABLE,
    ;

    companion object {
        fun fromWireValue(value: String): TtsDoneReason? =
            entries.firstOrNull { it.name == value }
    }
}

data class TtsSpeakRequest(
    val utteranceId: String,
    val text: String,
    val ownerPluginId: String? = null,
)

data class TtsStopRequest(
    val utteranceId: String,
    val ownerPluginId: String? = null,
)

sealed interface TtsValidationResult<out T> {
    data class Valid<T>(val value: T) : TtsValidationResult<T>
    data class Invalid(val reason: String) : TtsValidationResult<Nothing>
}

/** Pure TTS protocol v1 validation and normalization with no Android dependencies. */
object TtsContract {
    const val VERSION = 1

    /**
     * The same budget a notice body gets, deliberately: what a plugin may show
     * is what it may read out, so "speak the message I just displayed" never
     * has to be "speak most of it". Long enough to be a minute of talking, so
     * it is a ceiling, not an invitation.
     */
    const val MAX_TEXT_CHARS = 1024
    const val MAX_UTTERANCE_ID_CHARS = 64
    const val MAX_MESSAGES_PER_SECOND = 5

    const val ERROR_INVALID_TTS = "INVALID_TTS"
    const val ERROR_TTS_RATE_LIMITED = "TTS_RATE_LIMITED"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    fun normalizeText(value: String): String = value
        .replace(LINE_BREAKS, " ")
        .trim()

    fun validateSpeak(
        payload: JSONObject,
        requireOwner: Boolean = false,
    ): TtsValidationResult<TtsSpeakRequest> {
        val utteranceId = payload.opt("utteranceId") as? String
            ?: return invalid()
        val text = payload.opt("text") as? String
            ?: return invalid()
        if (!validUtteranceId(utteranceId)) return invalid()
        val normalizedText = normalizeText(text)
        if (normalizedText.isBlank() || normalizedText.length > MAX_TEXT_CHARS) return invalid()
        val owner = if (requireOwner) {
            val value = payload.opt("ownerPluginId") as? String ?: return invalid()
            value.takeIf(com.anezium.rokidbus.shared.plugin.PluginDescriptor::isValidId)
                ?: return invalid()
        } else {
            null
        }
        return TtsValidationResult.Valid(
            TtsSpeakRequest(
                utteranceId = utteranceId,
                text = normalizedText,
                ownerPluginId = owner,
            ),
        )
    }

    fun validateStop(
        payload: JSONObject,
        requireOwner: Boolean = false,
    ): TtsValidationResult<TtsStopRequest> {
        val utteranceId = payload.opt("utteranceId") as? String
            ?: return invalid()
        if (!validUtteranceId(utteranceId)) return invalid()
        val owner = if (requireOwner) {
            val value = payload.opt("ownerPluginId") as? String ?: return invalid()
            value.takeIf(com.anezium.rokidbus.shared.plugin.PluginDescriptor::isValidId)
                ?: return invalid()
        } else {
            null
        }
        return TtsValidationResult.Valid(TtsStopRequest(utteranceId, owner))
    }

    fun validateStarted(payload: JSONObject): TtsValidationResult<TtsStopRequest> =
        validateStop(payload, requireOwner = true)

    fun validateDone(payload: JSONObject): TtsValidationResult<Pair<TtsStopRequest, TtsDoneReason>> {
        val stopped = validateStop(payload, requireOwner = true)
        if (stopped !is TtsValidationResult.Valid) return invalid()
        val reason = (payload.opt("reason") as? String)?.let(TtsDoneReason::fromWireValue)
            ?: return invalid()
        return TtsValidationResult.Valid(stopped.value to reason)
    }

    fun speakPayload(utteranceId: String, text: String): JSONObject = JSONObject()
        .put("utteranceId", utteranceId)
        .put("text", normalizeText(text))

    fun stopPayload(utteranceId: String): JSONObject = JSONObject()
        .put("utteranceId", utteranceId)

    fun withOwner(payload: JSONObject, ownerPluginId: String): JSONObject =
        JSONObject(payload.toString()).put("ownerPluginId", ownerPluginId)

    fun startedPayload(ownerPluginId: String, utteranceId: String): JSONObject = JSONObject()
        .put("utteranceId", utteranceId)
        .put("ownerPluginId", ownerPluginId)

    fun donePayload(
        ownerPluginId: String,
        utteranceId: String,
        reason: TtsDoneReason,
    ): JSONObject = startedPayload(ownerPluginId, utteranceId)
        .put("reason", reason.name)

    private fun validUtteranceId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_UTTERANCE_ID_CHARS

    private fun invalid() = TtsValidationResult.Invalid(ERROR_INVALID_TTS)

    private val LINE_BREAKS = Regex("[\\r\\n]+")
}
