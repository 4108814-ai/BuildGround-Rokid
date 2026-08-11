package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.RemotePointerAction
import com.anezium.rokidbus.shared.RemotePointerCommand
import com.anezium.rokidbus.shared.RemotePointerErrorCode
import com.anezium.rokidbus.shared.RemotePointerResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePointerSequenceGateTest {
    private val streamA = "pointer_aaaaaaaaaaaaaaaa"
    private val streamB = "pointer_bbbbbbbbbbbbbbbb"
    private val gate = RemotePointerSequenceGate(maximumResults = 4, maximumRetiredStreams = 2)

    @Test
    fun `stream must start with show`() {
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_NOT_STARTED),
            gate.reserve(command(streamA, 1L, RemotePointerAction.MOVE)),
        )
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 2L, RemotePointerAction.SHOW)),
        )
    }

    @Test
    fun `sequence is reserved before effect and completed replay returns prior result`() {
        val command = command(streamA, 1L, RemotePointerAction.CLICK)
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 1L, RemotePointerAction.SHOW)),
        )
        val click = command.copy(sequence = 2L)
        assertEquals(RemotePointerReservation.New, gate.reserve(click))
        assertEquals(RemotePointerReservation.InFlight, gate.reserve(click))

        val result = RemotePointerResult(streamA, 2L, RemotePointerAction.CLICK)
        gate.complete(result)
        assertEquals(RemotePointerReservation.Completed(result), gate.reserve(click))
    }

    @Test
    fun `older uncached sequence cannot move or click again`() {
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 10L, RemotePointerAction.SHOW)),
        )
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STALE_SEQUENCE),
            gate.reserve(command(streamA, 9L, RemotePointerAction.CLICK)),
        )
    }

    @Test
    fun `new show retires old stream and delayed old traffic cannot revive it`() {
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 1L, RemotePointerAction.SHOW)),
        )
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_NOT_STARTED),
            gate.reserve(command(streamB, 1L, RemotePointerAction.MOVE)),
        )
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamB, 2L, RemotePointerAction.SHOW)),
        )
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_RETIRED),
            gate.reserve(command(streamA, 3L, RemotePointerAction.SHOW)),
        )
    }

    @Test
    fun `retired stream memory is bounded`() {
        val streamC = "pointer_cccccccccccccccc"
        val streamD = "pointer_dddddddddddddddd"
        gate.reserve(command(streamA, 1L, RemotePointerAction.SHOW))
        gate.reserve(command(streamB, 1L, RemotePointerAction.SHOW))
        gate.reserve(command(streamC, 1L, RemotePointerAction.SHOW))
        gate.reserve(command(streamD, 1L, RemotePointerAction.SHOW))

        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 2L, RemotePointerAction.SHOW)),
        )
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_RETIRED),
            gate.reserve(command(streamC, 2L, RemotePointerAction.SHOW)),
        )
    }

    @Test
    fun `link loss retires active stream and requires a fresh show stream`() {
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamA, 1L, RemotePointerAction.SHOW)),
        )
        assertEquals(true, gate.isReserved(command(streamA, 1L, RemotePointerAction.SHOW)))

        gate.onLinkLost()

        assertEquals(false, gate.isReserved(command(streamA, 1L, RemotePointerAction.SHOW)))
        assertEquals(
            RemotePointerReservation.Rejected(RemotePointerErrorCode.STREAM_RETIRED),
            gate.reserve(command(streamA, 2L, RemotePointerAction.SHOW)),
        )
        assertEquals(
            RemotePointerReservation.New,
            gate.reserve(command(streamB, 1L, RemotePointerAction.SHOW)),
        )
    }

    private fun command(
        streamId: String,
        sequence: Long,
        action: RemotePointerAction,
    ): RemotePointerCommand = if (action == RemotePointerAction.HIDE) {
        RemotePointerCommand(streamId, sequence, action)
    } else {
        RemotePointerCommand(streamId, sequence, action, 0.5, 0.5)
    }
}
