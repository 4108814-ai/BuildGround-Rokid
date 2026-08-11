package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.RemotePointerAction
import com.anezium.rokidbus.shared.RemotePointerCommand
import com.anezium.rokidbus.shared.RemotePointerContract
import com.anezium.rokidbus.shared.RemotePointerErrorCode
import com.anezium.rokidbus.shared.RemotePointerResult
import org.json.JSONObject

/** Adapts pointer bus commands to the AccessibilityService-owned cursor controller. */
internal object RemotePointerHubBridge {
    private var sender: ((String, JSONObject) -> Boolean)? = null
    private val sequenceGate = RemotePointerSequenceGate(
        maximumResults = MAX_POINTER_RESULTS,
        maximumRetiredStreams = MAX_RETIRED_STREAMS,
    )

    fun initialize(send: (String, JSONObject) -> Boolean) {
        sender = send
    }

    fun handle(payload: JSONObject): Boolean {
        val command = RemotePointerContract.parseCommand(payload) ?: return false
        val reservation = synchronized(sequenceGate) {
            sequenceGate.reserve(command).also { reserved ->
                if (reserved == RemotePointerReservation.New) perform(command)
            }
        }
        when (reservation) {
            RemotePointerReservation.New -> Unit
            RemotePointerReservation.InFlight -> Unit
            is RemotePointerReservation.Completed -> sendResult(reservation.result)
            is RemotePointerReservation.Rejected -> sendResult(
                RemotePointerResult(
                    streamId = command.streamId,
                    sequence = command.sequence,
                    action = command.action,
                    errorCode = reservation.errorCode,
                ),
            )
        }
        return true
    }

    fun onLinkLost() {
        sequenceGate.onLinkLost()
        RemotePointerController.onLinkLost()
    }

    private fun perform(command: RemotePointerCommand) {
        RemotePointerController.perform(
            command = command,
            isStillReserved = { sequenceGate.isReserved(command) },
        ) { execution ->
            val result = RemotePointerResult(
                streamId = command.streamId,
                sequence = command.sequence,
                action = command.action,
                errorCode = when (execution) {
                    RemotePointerExecutionResult.PERFORMED -> null
                    RemotePointerExecutionResult.SERVICE_UNAVAILABLE ->
                        RemotePointerErrorCode.SERVICE_UNAVAILABLE
                    RemotePointerExecutionResult.ACTION_UNAVAILABLE ->
                        RemotePointerErrorCode.ACTION_UNAVAILABLE
                    RemotePointerExecutionResult.GESTURE_CANCELLED ->
                        RemotePointerErrorCode.GESTURE_CANCELLED
                    RemotePointerExecutionResult.COMMAND_RETIRED ->
                        RemotePointerErrorCode.STREAM_RETIRED
                },
            )
            sequenceGate.complete(result)
            sendResult(result)
        }
    }

    private fun sendResult(result: RemotePointerResult) {
        sender?.invoke(RemotePointerContract.RESULT_PATH, RemotePointerContract.result(result))
    }

    private const val MAX_POINTER_RESULTS = 128
    private const val MAX_RETIRED_STREAMS = 32
}

internal sealed interface RemotePointerReservation {
    data object New : RemotePointerReservation
    data object InFlight : RemotePointerReservation
    data class Completed(val result: RemotePointerResult) : RemotePointerReservation
    data class Rejected(val errorCode: RemotePointerErrorCode) : RemotePointerReservation
}

private data class RemotePointerCommandKey(val streamId: String, val sequence: Long)

/**
 * Synchronized because CXR and SPP can call the glasses hub from different threads. A stream must
 * begin with SHOW; switching streams retires the previous id so a delayed old command cannot
 * revive it. Sequence reservation happens before any asynchronous click effect.
 */
internal class RemotePointerSequenceGate(
    private val maximumResults: Int,
    private val maximumRetiredStreams: Int,
) {
    private val results = object : LinkedHashMap<RemotePointerCommandKey, RemotePointerResult?>(
        32,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RemotePointerCommandKey, RemotePointerResult?>?,
        ): Boolean = size > maximumResults
    }
    private val retiredStreams = linkedSetOf<String>()
    private var activeStreamId: String? = null
    private var highestSequence = 0L

    init {
        require(maximumResults > 0)
        require(maximumRetiredStreams > 0)
    }

    @Synchronized
    fun reserve(command: RemotePointerCommand): RemotePointerReservation {
        val key = RemotePointerCommandKey(command.streamId, command.sequence)
        results[key]?.let { return RemotePointerReservation.Completed(it) }
        if (results.containsKey(key)) return RemotePointerReservation.InFlight
        if (command.streamId in retiredStreams) {
            return RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_RETIRED)
        }

        val active = activeStreamId
        if (active == null) {
            if (command.action != RemotePointerAction.SHOW) {
                return RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_NOT_STARTED)
            }
            activeStreamId = command.streamId
            highestSequence = 0L
        } else if (active != command.streamId) {
            if (command.action != RemotePointerAction.SHOW) {
                return RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_NOT_STARTED)
            }
            retire(active)
            activeStreamId = command.streamId
            highestSequence = 0L
        }

        if (command.sequence <= highestSequence) {
            return RemotePointerReservation.Rejected(RemotePointerErrorCode.STALE_SEQUENCE)
        }
        highestSequence = command.sequence
        results[key] = null
        return RemotePointerReservation.New
    }

    @Synchronized
    fun complete(result: RemotePointerResult) {
        results[RemotePointerCommandKey(result.streamId, result.sequence)] = result
    }

    @Synchronized
    fun isReserved(command: RemotePointerCommand): Boolean {
        val key = RemotePointerCommandKey(command.streamId, command.sequence)
        return activeStreamId == command.streamId &&
            results.containsKey(key) &&
            results[key] == null
    }

    @Synchronized
    fun onLinkLost() {
        activeStreamId?.let(::retire)
        activeStreamId = null
        highestSequence = 0L
    }

    private fun retire(streamId: String) {
        retiredStreams += streamId
        while (retiredStreams.size > maximumRetiredStreams) {
            retiredStreams.remove(retiredStreams.first())
        }
    }
}
