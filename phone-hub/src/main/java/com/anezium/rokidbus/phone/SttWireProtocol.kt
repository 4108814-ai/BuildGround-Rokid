package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage
import org.json.JSONObject
import java.util.Locale

internal object SttWireProtocol {
    const val SESSION_START_PATH = "/stt/session/start"
    const val SESSION_STOP_PATH = "/stt/session/stop"
    const val STATE_PATH = "/stt/state"
    const val PARTIAL_PATH = "/stt/partial"
    const val FINAL_PATH = "/stt/final"
    const val SESSION_ENDED_PATH = "/stt/session/ended"

    data class StartRequest(
        val language: TranscriptionLanguage?,
    )

    fun parseStart(payload: JSONObject): StartRequest? {
        if (payload.optInt("version", -1) != 1) return null
        if (payload.optString("mode") != "utterance") return null
        val languageId = payload.optString("language").trim().lowercase(Locale.US)
        val language = TranscriptionLanguage.entries.firstOrNull { it.id == languageId }
        return StartRequest(language)
    }

    fun startDenialReason(result: SpeechStartResult): String = when (result) {
        SpeechStartResult.BUSY -> "BUSY"
        SpeechStartResult.NO_LINK -> "NO_LINK"
        SpeechStartResult.NOT_READY -> "NOT_READY"
        SpeechStartResult.START_FAILED,
        SpeechStartResult.OK,
        -> "START_FAILED"
    }

    fun stateId(sessionId: String, sequence: Long): String = "$sessionId:s$sequence"

    fun partialId(sessionId: String, sequence: Long): String = "$sessionId:p$sequence"

    fun finalId(sessionId: String): String = "$sessionId:final"

    fun endedId(sessionId: String): String = "$sessionId:ended"

    fun statePayload(
        pluginId: String,
        sessionId: String,
        state: SpeechSessionState,
    ): JSONObject = basePayload(pluginId, sessionId)
        .put("state", state.name.lowercase(Locale.US))

    fun partialPayload(
        pluginId: String,
        sessionId: String,
        text: String,
        sequence: Long,
    ): JSONObject = basePayload(pluginId, sessionId)
        .put("text", text)
        .put("seq", sequence)

    fun finalPayload(
        pluginId: String,
        sessionId: String,
        text: String,
    ): JSONObject = basePayload(pluginId, sessionId)
        .put("text", text)

    fun endedPayload(
        pluginId: String,
        sessionId: String,
        reason: SpeechEndReason,
        error: SttError?,
    ): JSONObject = endedPayload(
        pluginId = pluginId,
        sessionId = sessionId,
        reason = when (reason) {
            SpeechEndReason.COMPLETED -> "completed"
            SpeechEndReason.CANCELLED -> "cancelled"
            SpeechEndReason.NO_SPEECH -> "no_speech"
            SpeechEndReason.ERROR -> "error"
            SpeechEndReason.LINK_LOST -> "link_lost"
        },
        error = error,
    )

    fun revokedPayload(pluginId: String, sessionId: String): JSONObject =
        endedPayload(pluginId, sessionId, "revoked", null)

    private fun endedPayload(
        pluginId: String,
        sessionId: String,
        reason: String,
        error: SttError?,
    ): JSONObject = basePayload(pluginId, sessionId)
        .put("reason", reason)
        .apply {
            if (error != null) {
                put(
                    "error",
                    JSONObject()
                        .put("kind", error.kind.name)
                        .apply {
                            error.providerLabel?.takeIf(String::isNotBlank)?.let {
                                put("provider", it)
                            }
                            error.detail?.takeIf(String::isNotBlank)?.let {
                                put("detail", it)
                            }
                        },
                )
            }
        }

    private fun basePayload(pluginId: String, sessionId: String): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("sessionId", sessionId)
            .put("pluginId", pluginId)
}
