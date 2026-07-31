package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmWifiAutomationPolicyTest {
    @Test
    fun `setup automates wifi only while accessibility is armed and wifi is off`() {
        assertTrue(SelfArmWifiAutomationPolicy.shouldAutomate(true, false))
        assertFalse(SelfArmWifiAutomationPolicy.shouldAutomate(false, false))
        assertFalse(SelfArmWifiAutomationPolicy.shouldAutomate(true, true))
    }

    @Test
    fun `wifi automation and network settling are strictly bounded`() {
        assertTrue(SelfArmWifiAutomationPolicy.AUTOMATION_TIMEOUT_MS in 10_000L..60_000L)
        assertTrue(SelfArmWifiAutomationPolicy.NETWORK_SETTLE_TIMEOUT_MS in 5_000L..60_000L)
        assertTrue(SelfArmWifiAutomationPolicy.MAX_TOGGLE_ATTEMPTS in 1..3)
    }
}
