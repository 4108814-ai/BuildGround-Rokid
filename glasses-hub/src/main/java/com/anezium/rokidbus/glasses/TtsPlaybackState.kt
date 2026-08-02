package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.TtsDoneReason

internal data class ActiveTtsUtterance(
    val ownerPluginId: String,
    val utteranceId: String,
    val engineId: String,
    val text: String,
    val started: Boolean = false,
    val terminalReason: TtsDoneReason? = null,
)

internal data class TtsStartedEvent(
    val ownerPluginId: String,
    val utteranceId: String,
)

internal data class TtsDoneEvent(
    val ownerPluginId: String,
    val utteranceId: String,
    val reason: TtsDoneReason,
)

internal data class TtsAcceptResult(
    val active: ActiveTtsUtterance,
    val preempted: TtsDoneEvent?,
)

/** Single-slot TTS policy. Binder and Android lifecycle details deliberately stay outside it. */
internal class TtsPlaybackState {
    private var active: ActiveTtsUtterance? = null

    fun accept(
        ownerPluginId: String,
        utteranceId: String,
        engineId: String,
        text: String,
    ): TtsAcceptResult {
        val preempted = active?.toDone(TtsDoneReason.PREEMPTED)
        val next = ActiveTtsUtterance(ownerPluginId, utteranceId, engineId, text)
        active = next
        return TtsAcceptResult(next, preempted)
    }

    fun current(): ActiveTtsUtterance? = active

    fun started(engineId: String): TtsStartedEvent? {
        val current = active ?: return null
        if (current.engineId != engineId || current.started) return null
        active = current.copy(started = true)
        return TtsStartedEvent(current.ownerPluginId, current.utteranceId)
    }

    fun requestStop(
        ownerPluginId: String,
        utteranceId: String,
        reason: TtsDoneReason = TtsDoneReason.STOPPED,
    ): ActiveTtsUtterance? {
        val current = active ?: return null
        if (current.ownerPluginId != ownerPluginId || current.utteranceId != utteranceId) return null
        return requestCancellation(current, reason)
    }

    /** Owner-free cancellation seam for the later microphone interlock. */
    fun cancelCurrent(reason: TtsDoneReason = TtsDoneReason.STOPPED): ActiveTtsUtterance? {
        val current = active ?: return null
        return requestCancellation(current, reason)
    }

    fun stopped(engineId: String): TtsDoneEvent? {
        val current = active ?: return null
        if (current.engineId != engineId) return null
        active = null
        return current.toDone(current.terminalReason ?: TtsDoneReason.COMPLETED)
    }

    fun unavailable(): TtsDoneEvent? {
        val current = active ?: return null
        active = null
        return current.toDone(TtsDoneReason.UNAVAILABLE)
    }

    private fun requestCancellation(
        current: ActiveTtsUtterance,
        reason: TtsDoneReason,
    ): ActiveTtsUtterance? {
        if (current.terminalReason != null) return null
        val stopping = current.copy(terminalReason = reason)
        active = stopping
        return stopping
    }

    private fun ActiveTtsUtterance.toDone(reason: TtsDoneReason) = TtsDoneEvent(
        ownerPluginId = ownerPluginId,
        utteranceId = utteranceId,
        reason = reason,
    )
}

/** Retains terminal events until the phone transport accepts each one, in FIFO order. */
internal class TtsDoneOutbox {
    private val pending = ArrayDeque<TtsDoneEvent>()

    fun enqueue(event: TtsDoneEvent) {
        pending.addLast(event)
    }

    fun flush(send: (TtsDoneEvent) -> Boolean) {
        while (pending.isNotEmpty()) {
            if (!send(pending.first())) return
            pending.removeFirst()
        }
    }

    fun size(): Int = pending.size
}
