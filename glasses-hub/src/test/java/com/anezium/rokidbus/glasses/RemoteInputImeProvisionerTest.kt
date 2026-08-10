package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputImeProvisionerTest {
    private val nexus = "com.anezium.rokidbus.glasses/.NexusRemoteInputMethodService"

    @Test
    fun `adds Nexus without removing enabled keyboards`() {
        assertEquals(
            "com.example/.Ime:$nexus",
            RemoteInputImeProvisioner.enabledMethodsWithNexus("com.example/.Ime", nexus),
        )
    }

    @Test
    fun `does not duplicate Nexus`() {
        assertEquals(
            "com.example/.Ime:$nexus",
            RemoteInputImeProvisioner.enabledMethodsWithNexus("com.example/.Ime:$nexus", nexus),
        )
    }

    @Test
    fun `selects Nexus only when no keyboard is selected`() {
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus(null, nexus))
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus("", nexus))
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus(nexus, nexus))
        assertFalse(RemoteInputImeProvisioner.shouldSelectNexus("com.example/.Ime", nexus))
    }
}
