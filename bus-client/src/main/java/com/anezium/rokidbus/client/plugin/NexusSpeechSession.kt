package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class NexusSpeechState {
    LISTENING,
    RECOGNIZING,
    PROCESSING,
}

data class NexusSpeechError(
    val kind: String,
    val provider: String?,
    val detail: String?,
)

enum class NexusSpeechStopReason {
    COMPLETED,
    CANCELLED,
    NO_SPEECH,
    ERROR,
    LINK_LOST,
    REVOKED,
    DENIED_BUSY,
    DENIED_NO_LINK,
    DENIED_NOT_READY,
    DENIED_START_FAILED,
    DENIED_INVALID,
}

interface NexusSpeechCallbacks {
    fun onSpeechStarted(realtime: Boolean)
    fun onSpeechState(state: NexusSpeechState)
    fun onSpeechPartial(text: String)
    fun onSpeechFinal(text: String)
    fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?)
}

class NexusSpeechSession internal constructor(
    private val client: NexusPluginClient,
    private val callbacks: NexusSpeechCallbacks,
) {
    private enum class State {
        IDLE,
        PENDING,
        ACTIVE,
    }

    private val stateLock = Any()
    private var state = State.IDLE
    private var sessionId: String? = null

    val isActive: Boolean
        get() = synchronized(stateLock) { state == State.ACTIVE }

    fun start(language: String? = null): NexusSdkResult {
        return synchronized(stateLock) {
            if (!client.isApprovedForSpeech()) return@synchronized NexusSdkResult.NOT_REGISTERED
            if (!client.hasCapability(PluginCapability.STT)) {
                return@synchronized NexusSdkResult.CAPABILITY_NOT_GRANTED
            }
            if (state != State.IDLE) return@synchronized NexusSdkResult.SENT
            if (!client.registerSpeechSession(this)) {
                return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            }

            val requestId = UUID.randomUUID().toString()
            state = State.PENDING
            if (client.sendSpeechStart(this, requestId, language)) {
                NexusSdkResult.SENT
            } else {
                clearState()
                client.unregisterSpeechSession(this)
                NexusSdkResult.NOT_REGISTERED
            }
        }
    }

    fun stop() {
        synchronized(stateLock) {
            if (state != State.ACTIVE) return
            val currentSessionId = sessionId ?: return
            client.sendSpeechStop(this, UUID.randomUUID().toString(), currentSessionId)
            if (state == State.ACTIVE && sessionId == currentSessionId) {
                finish(NexusSpeechStopReason.CANCELLED, null)
            }
        }
    }

    internal fun onStartReply(payload: JSONObject) {
        synchronized(stateLock) {
            if (state != State.PENDING) return
            if (!payload.optBoolean("accepted", false)) {
                finish(mapDeniedReason(payload.optString("reason")), null)
                return
            }

            val acceptedSessionId = payload.optString("sessionId")
            if (acceptedSessionId.isBlank() || !payload.has("realtime")) {
                finish(NexusSpeechStopReason.ERROR, null)
                return
            }
            sessionId = acceptedSessionId
            state = State.ACTIVE
            callbacks.onSpeechStarted(payload.optBoolean("realtime", false))
        }
    }

    internal fun onState(payload: JSONObject) {
        synchronized(stateLock) {
            if (!matchesActiveSession(payload)) return
            val wireState = payload.optString("state").trim().uppercase(Locale.US)
            val speechState = NexusSpeechState.entries.firstOrNull { it.name == wireState }
                ?: return
            callbacks.onSpeechState(speechState)
        }
    }

    internal fun onPartial(payload: JSONObject) {
        synchronized(stateLock) {
            if (!matchesActiveSession(payload)) return
            callbacks.onSpeechPartial(payload.optString("text"))
        }
    }

    internal fun onFinal(payload: JSONObject) {
        synchronized(stateLock) {
            if (!matchesActiveSession(payload)) return
            callbacks.onSpeechFinal(payload.optString("text"))
        }
    }

    internal fun onEnded(payload: JSONObject) {
        synchronized(stateLock) {
            if (!matchesActiveSession(payload)) return
            val error = payload.optJSONObject("error")?.let { errorPayload ->
                NexusSpeechError(
                    kind = errorPayload.optString("kind"),
                    provider = errorPayload.optString("provider").takeIf(String::isNotBlank),
                    detail = errorPayload.optString("detail").takeIf(String::isNotBlank),
                )
            }
            finish(mapEndedReason(payload.optString("reason")), error)
        }
    }

    internal fun onStopReply(@Suppress("UNUSED_PARAMETER") payload: JSONObject) = Unit

    internal fun terminate(reason: NexusSpeechStopReason, stopActiveSession: Boolean) {
        synchronized(stateLock) {
            if (state == State.IDLE) {
                client.unregisterSpeechSession(this)
                return
            }
            if (stopActiveSession && state == State.ACTIVE) {
                sessionId?.let { currentSessionId ->
                    client.sendSpeechStop(
                        this,
                        UUID.randomUUID().toString(),
                        currentSessionId,
                    )
                }
            }
            if (state != State.IDLE) finish(reason, null)
        }
    }

    private fun matchesActiveSession(payload: JSONObject): Boolean =
        state == State.ACTIVE && payload.optString("sessionId") == sessionId

    private fun finish(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
        if (state == State.IDLE) return
        clearState()
        client.unregisterSpeechSession(this)
        callbacks.onSpeechStopped(reason, error)
    }

    private fun clearState() {
        state = State.IDLE
        sessionId = null
    }

    private fun mapDeniedReason(reason: String): NexusSpeechStopReason = when (
        reason.trim().uppercase(Locale.US)
    ) {
        "BUSY" -> NexusSpeechStopReason.DENIED_BUSY
        "NO_LINK" -> NexusSpeechStopReason.DENIED_NO_LINK
        "NOT_READY" -> NexusSpeechStopReason.DENIED_NOT_READY
        "START_FAILED" -> NexusSpeechStopReason.DENIED_START_FAILED
        "INVALID_REQUEST" -> NexusSpeechStopReason.DENIED_INVALID
        else -> NexusSpeechStopReason.DENIED_INVALID
    }

    private fun mapEndedReason(reason: String): NexusSpeechStopReason = when (
        reason.trim().lowercase(Locale.US)
    ) {
        "completed" -> NexusSpeechStopReason.COMPLETED
        "cancelled" -> NexusSpeechStopReason.CANCELLED
        "no_speech" -> NexusSpeechStopReason.NO_SPEECH
        "error" -> NexusSpeechStopReason.ERROR
        "link_lost" -> NexusSpeechStopReason.LINK_LOST
        "revoked" -> NexusSpeechStopReason.REVOKED
        else -> NexusSpeechStopReason.ERROR
    }
}

fun NexusPluginClient.speechSession(callbacks: NexusSpeechCallbacks): NexusSpeechSession =
    NexusSpeechSession(this, callbacks)

internal const val NEXUS_STT_SESSION_START_PATH = "/stt/session/start"
internal const val NEXUS_STT_SESSION_START_REPLY_PATH = "/stt/session/start/reply"
internal const val NEXUS_STT_SESSION_STOP_PATH = "/stt/session/stop"
internal const val NEXUS_STT_SESSION_STOP_REPLY_PATH = "/stt/session/stop/reply"
internal const val NEXUS_STT_STATE_PATH = "/stt/state"
internal const val NEXUS_STT_PARTIAL_PATH = "/stt/partial"
internal const val NEXUS_STT_FINAL_PATH = "/stt/final"
internal const val NEXUS_STT_SESSION_ENDED_PATH = "/stt/session/ended"
