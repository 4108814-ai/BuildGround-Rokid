package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmBootRepairPolicyTest {
    private fun blocker(
        autoRepairEnabled: Boolean = true,
        alreadyAttemptedThisBoot: Boolean = false,
        bridgePresumedDead: Boolean = true,
        wifiEnabled: Boolean = false,
        bootstrapComplete: Boolean = true,
        setupSessionActive: Boolean = false,
        displayInteractive: Boolean = true,
    ): String? = SelfArmBootRepairPolicy.bootAttemptBlocker(
        autoRepairEnabled = autoRepairEnabled,
        alreadyAttemptedThisBoot = alreadyAttemptedThisBoot,
        bridgePresumedDead = bridgePresumedDead,
        wifiEnabled = wifiEnabled,
        bootstrapComplete = bootstrapComplete,
        setupSessionActive = setupSessionActive,
        displayInteractive = displayInteractive,
    )

    @Test
    fun `the attempt runs only when every precondition holds`() {
        assertNull(blocker())
    }

    @Test
    fun `each failed precondition names itself`() {
        assertEquals("auto_repair_disabled", blocker(autoRepairEnabled = false))
        assertEquals("already_attempted_this_boot", blocker(alreadyAttemptedThisBoot = true))
        assertEquals("bridge_not_presumed_dead", blocker(bridgePresumedDead = false))
        assertEquals("wifi_already_enabled", blocker(wifiEnabled = true))
        assertEquals("bootstrap_incomplete", blocker(bootstrapComplete = false))
        assertEquals("setup_session_active", blocker(setupSessionActive = true))
        assertEquals("display_not_interactive", blocker(displayInteractive = false))
    }

    @Test
    fun `the owner's switch outranks the latch in the log`() {
        // Both hold; the message must say the owner opted out, not that a popup already fired.
        assertEquals(
            "auto_repair_disabled",
            blocker(autoRepairEnabled = false, alreadyAttemptedThisBoot = true),
        )
    }

    private fun restore(
        wifiWasOffBeforeRepair: Boolean = true,
        wifiEnabledNow: Boolean = true,
        bridgePresumedDead: Boolean = false,
        wifiHubOwned: Boolean = false,
        setupSessionActive: Boolean = false,
        mediaSyncSessionActive: Boolean = false,
        cameraSessionActive: Boolean = false,
    ): Boolean = SelfArmBootRepairPolicy.shouldRestoreWifi(
        wifiWasOffBeforeRepair = wifiWasOffBeforeRepair,
        wifiEnabledNow = wifiEnabledNow,
        bridgePresumedDead = bridgePresumedDead,
        wifiHubOwned = wifiHubOwned,
        setupSessionActive = setupSessionActive,
        mediaSyncSessionActive = mediaSyncSessionActive,
        cameraSessionActive = cameraSessionActive,
    )

    @Test
    fun `wifi goes back off only when the repair owes it and the bridge can do it`() {
        assertTrue(restore())
    }

    @Test
    fun `a radio the repair did not turn on is never touched`() {
        assertFalse(restore(wifiWasOffBeforeRepair = false))
    }

    @Test
    fun `a radio already off again is left alone`() {
        assertFalse(restore(wifiEnabledNow = false))
    }

    @Test
    fun `a dead bridge means the radio stays as the automation left it`() {
        // Restoring would take a second Settings run, and the automation already had its turn.
        assertFalse(restore(bridgePresumedDead = true))
    }

    @Test
    fun `every live radio owner blocks the restore`() {
        assertFalse(restore(wifiHubOwned = true))
        assertFalse(restore(setupSessionActive = true))
        assertFalse(restore(mediaSyncSessionActive = true))
        // The camera never claims ownership of a radio it found already up.
        assertFalse(restore(cameraSessionActive = true))
    }
}
