package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputSessionGateTest {
    @Test
    fun acceptsOnlyTheExactNextSequenceForTheActiveSession() {
        val gate = RemoteInputSessionGate()
        gate.open("session-a")

        assertEquals(RemoteInputResultCode.APPLIED, gate.evaluate("session-a", 1L))
        gate.advance(1L)
        assertEquals(2L, gate.nextSequence)
        assertEquals(RemoteInputResultCode.DUPLICATE_SEQUENCE, gate.evaluate("session-a", 1L))
        assertEquals(RemoteInputResultCode.OUT_OF_ORDER_SEQUENCE, gate.evaluate("session-a", 3L))
        assertEquals(RemoteInputResultCode.SESSION_MISMATCH, gate.evaluate("session-b", 2L))
    }

    @Test
    fun openingAndClosingSessionsResetTheSequence() {
        val gate = RemoteInputSessionGate()
        assertFalse(gate.active)

        gate.open("first")
        gate.advance(1L)
        gate.close()
        assertFalse(gate.active)
        assertEquals(RemoteInputResultCode.NO_ACTIVE_SESSION, gate.evaluate("first", 2L))

        gate.open("second")
        assertTrue(gate.active)
        assertEquals(1L, gate.nextSequence)
        assertEquals(RemoteInputResultCode.APPLIED, gate.evaluate("second", 1L))
    }
}
