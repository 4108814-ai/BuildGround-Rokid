package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsValidationResult
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.UUID

enum class NexusTtsDoneReason {
    COMPLETED,
    STOPPED,
    PREEMPTED,
    UNAVAILABLE,
}

interface NexusTtsCallbacks {
    fun onTtsStarted(utteranceId: String)
    fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason)
}

/** One plugin's typed view of the glasses' single-slot speech output. */
class NexusTtsSession internal constructor(
    private val client: NexusPluginClient,
    private val callbacks: NexusTtsCallbacks,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private val stateLock = Any()
    private val acceptedIds = linkedSetOf<String>()
    private val startedIds = linkedSetOf<String>()
    private val recentCommands = ArrayDeque<Long>()
    private var currentUtteranceId: String? = null
    private var closed = false

    val activeUtteranceId: String?
        get() = synchronized(stateLock) { currentUtteranceId }

    /**
     * Speaks [text] aloud on the glasses.
     *
     * Pass [utteranceId] to name the utterance yourself — a message key, a row
     * id, whatever the caller already uses — and the callbacks come back
     * carrying it, so an answer never has to be matched by timing. Leave it out
     * and the session invents one for callers who only ever say one thing.
     */
    @JvmOverloads
    fun speak(
        text: String,
        utteranceId: String = UUID.randomUUID().toString(),
    ): NexusSdkResult = synchronized(stateLock) {
        if (closed || !client.isApprovedForTts()) return@synchronized NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.TTS) || !client.supportsTts) {
            return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        if (utteranceId in acceptedIds) return@synchronized NexusSdkResult.INVALID_PAYLOAD
        val payload = TtsContract.speakPayload(utteranceId, text)
        if (TtsContract.validateSpeak(payload) !is TtsValidationResult.Valid) {
            return@synchronized NexusSdkResult.INVALID_PAYLOAD
        }
        if (!admit(nowMs())) return@synchronized NexusSdkResult.TTS_RATE_LIMITED
        if (!client.registerTtsSession(this)) {
            return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        if (!client.sendTtsSpeak(this, UUID.randomUUID().toString(), payload)) {
            if (acceptedIds.isEmpty()) client.unregisterTtsSession(this)
            return@synchronized NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        acceptedIds += utteranceId
        currentUtteranceId = utteranceId
        NexusSdkResult.SENT
    }

    fun stop(): NexusSdkResult = synchronized(stateLock) {
        if (closed || !client.isApprovedForTts()) return@synchronized NexusSdkResult.NOT_REGISTERED
        val utteranceId = currentUtteranceId ?: return@synchronized NexusSdkResult.SENT
        if (!admit(nowMs())) return@synchronized NexusSdkResult.TTS_RATE_LIMITED
        if (client.sendTtsStop(this, UUID.randomUUID().toString(), utteranceId)) {
            NexusSdkResult.SENT
        } else {
            finish(utteranceId, NexusTtsDoneReason.UNAVAILABLE)
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
    }

    internal fun onStarted(payload: JSONObject) {
        synchronized(stateLock) {
            val utteranceId = payload.optString("utteranceId")
            if (utteranceId !in acceptedIds || !startedIds.add(utteranceId)) return
            callbacks.onTtsStarted(utteranceId)
        }
    }

    internal fun onDone(payload: JSONObject) {
        synchronized(stateLock) {
            val utteranceId = payload.optString("utteranceId")
            if (utteranceId !in acceptedIds) return
            val reason = TtsDoneReason.fromWireValue(payload.optString("reason"))
                ?.let { NexusTtsDoneReason.valueOf(it.name) }
                ?: NexusTtsDoneReason.UNAVAILABLE
            finish(utteranceId, reason)
        }
    }

    internal fun terminate(reason: NexusTtsDoneReason, stopCurrent: Boolean) {
        synchronized(stateLock) {
            if (closed && acceptedIds.isEmpty()) {
                client.unregisterTtsSession(this)
                return
            }
            if (stopCurrent) {
                currentUtteranceId?.let { utteranceId ->
                    client.sendTtsStop(this, UUID.randomUUID().toString(), utteranceId)
                }
            }
            val outstanding = acceptedIds.toList()
            acceptedIds.clear()
            startedIds.clear()
            currentUtteranceId = null
            client.unregisterTtsSession(this)
            outstanding.forEach { callbacks.onTtsDone(it, reason) }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        terminate(NexusTtsDoneReason.STOPPED, stopCurrent = true)
    }

    private fun finish(utteranceId: String, reason: NexusTtsDoneReason) {
        if (!acceptedIds.remove(utteranceId)) return
        startedIds.remove(utteranceId)
        if (currentUtteranceId == utteranceId) currentUtteranceId = acceptedIds.lastOrNull()
        if (acceptedIds.isEmpty()) client.unregisterTtsSession(this)
        callbacks.onTtsDone(utteranceId, reason)
    }

    private fun admit(now: Long): Boolean {
        while (recentCommands.isNotEmpty() && now - recentCommands.first() >= RATE_WINDOW_MS) {
            recentCommands.removeFirst()
        }
        if (recentCommands.size >= TtsContract.MAX_MESSAGES_PER_SECOND) return false
        recentCommands.addLast(now)
        return true
    }

    private companion object {
        const val RATE_WINDOW_MS = 1_000L
    }
}

fun NexusPluginClient.ttsSession(callbacks: NexusTtsCallbacks): NexusTtsSession =
    NexusTtsSession(this, callbacks)
