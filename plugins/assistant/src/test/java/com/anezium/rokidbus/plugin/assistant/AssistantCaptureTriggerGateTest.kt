package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCaptureTriggerGateTest {
    @Test
    fun `button start is claimed once per active edge`() {
        val gate = AssistantCaptureTriggerGate()

        assertTrue(gate.claimButtonStart())
        assertFalse(gate.claimButtonStart())

        gate.onButtonStop()

        assertTrue(gate.claimButtonStart())
    }

    @Test
    fun `gesture open is claimed once per stable gesture id`() {
        val gate = AssistantCaptureTriggerGate()

        assertFalse(gate.claimGestureOpen(""))
        assertTrue(gate.claimGestureOpen("gesture-1"))
        assertFalse(gate.claimGestureOpen("gesture-1"))
        assertTrue(gate.claimGestureOpen("gesture-2"))
    }

    @Test
    fun `gesture open claim suppresses a racing button start`() {
        val gate = AssistantCaptureTriggerGate()

        assertTrue(gate.claimGestureOpen("gesture-1"))
        assertFalse(gate.claimButtonStart())
    }

    @Test
    fun `button claim suppresses a racing gesture open`() {
        val gate = AssistantCaptureTriggerGate()

        assertTrue(gate.claimButtonStart())
        assertFalse(gate.claimGestureOpen("gesture-1"))
    }
}
