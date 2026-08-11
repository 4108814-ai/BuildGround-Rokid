package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePointerGestureCompletionGateTest {
    @Test
    fun `stale platform callback cannot complete a gesture started after service recreation`() {
        val completed = mutableListOf<RemotePointerExecutionResult>()
        val gate = RemotePointerGestureCompletionGate()
        val old = requireNotNull(gate.begin(completed::add))
        assertNull(gate.begin(completed::add))

        assertTrue(gate.cancel(RemotePointerExecutionResult.SERVICE_UNAVAILABLE))
        val current = requireNotNull(gate.begin(completed::add))

        assertFalse(gate.complete(old, RemotePointerExecutionResult.PERFORMED))
        assertEquals(listOf(RemotePointerExecutionResult.SERVICE_UNAVAILABLE), completed)
        assertTrue(gate.complete(current, RemotePointerExecutionResult.PERFORMED))
        assertEquals(
            listOf(
                RemotePointerExecutionResult.SERVICE_UNAVAILABLE,
                RemotePointerExecutionResult.PERFORMED,
            ),
            completed,
        )
        assertFalse(gate.cancel(RemotePointerExecutionResult.GESTURE_CANCELLED))
    }
}
