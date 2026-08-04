package com.anezium.rokidbus.glasses

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmBootRepairStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the switch defaults on and persists what the phone pushes`() {
        assertTrue(SelfArmBootRepairStore.isAutoRepairEnabled(context))
        SelfArmBootRepairStore.setAutoRepairEnabled(context, false)
        assertFalse(SelfArmBootRepairStore.isAutoRepairEnabled(context))
        SelfArmBootRepairStore.setAutoRepairEnabled(context, true)
        assertTrue(SelfArmBootRepairStore.isAutoRepairEnabled(context))
    }

    @Test
    fun `the latch holds for the boot it was claimed on`() {
        assertFalse(SelfArmBootRepairStore.hasAttemptedThisBoot(context))
        SelfArmBootRepairStore.recordBootAttempt(context)
        // A hub restart within the same boot reads the same instant back: no second popup.
        assertTrue(SelfArmBootRepairStore.hasAttemptedThisBoot(context))
    }

    @Test
    fun `a recording from another boot frees the latch`() {
        val staleInstant = SelfArmBridgeLivenessStore.currentBootInstantMillis() -
            SelfArmBridgeLivenessPolicy.BOOT_INSTANT_TOLERANCE_MS - 1_000L
        context.getSharedPreferences("selfarm_wireless", Context.MODE_PRIVATE)
            .edit()
            .putLong("boot_repair_attempt_boot_instant", staleInstant)
            .commit()
        assertFalse(SelfArmBootRepairStore.hasAttemptedThisBoot(context))
    }
}
