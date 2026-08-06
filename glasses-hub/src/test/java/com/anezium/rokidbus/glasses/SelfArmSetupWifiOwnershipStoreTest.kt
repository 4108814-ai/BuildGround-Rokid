package com.anezium.rokidbus.glasses

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmSetupWifiOwnershipStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun reset() {
        context.getSharedPreferences("selfarm_wireless", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `wifi already on never creates setup ownership`() {
        assertFalse(
            SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
                context,
                sessionId = "setup-1",
                wifiCurrentlyEnabled = true,
                nowMillis = 100L,
            ),
        )
        assertNull(SelfArmSetupWifiOwnershipStore.read(context))
    }

    @Test
    fun `pre-enable state and issued enable survive process-local state loss`() {
        assertTrue(
            SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
                context,
                sessionId = "setup-1",
                wifiCurrentlyEnabled = false,
                nowMillis = 100L,
            ),
        )
        assertFalse(SelfArmSetupWifiOwnershipStore.isNexusOwned(context))

        assertTrue(
            SelfArmSetupWifiOwnershipStore.markEnableIssued(
                context,
                sessionId = "setup-1",
                nowMillis = 200L,
            ),
        )

        assertTrue(SelfArmSetupWifiOwnershipStore.isNexusOwned(context))
        assertEquals(
            SelfArmSetupWifiOwnershipRecord(
                "setup-1",
                wifiWasEnabledBeforeSetup = false,
                enableIssued = true,
                enableRequestInFlight = false,
                recordedAtMillis = 200L,
            ),
            SelfArmSetupWifiOwnershipStore.read(context),
        )
    }

    @Test
    fun `user enabled wifi before Nexus issued a toggle is never owned`() {
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context,
            sessionId = "setup-1",
            wifiCurrentlyEnabled = false,
            nowMillis = 100L,
        )

        assertTrue(SelfArmSetupWifiOwnershipStore.discardUnissued(context, "setup-1"))
        assertFalse(SelfArmSetupWifiOwnershipStore.isNexusOwned(context))
    }

    @Test
    fun `an observed off radio clears issued ownership`() {
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context,
            sessionId = "setup-1",
            wifiCurrentlyEnabled = false,
            nowMillis = 100L,
        )
        SelfArmSetupWifiOwnershipStore.markEnableIssued(context, "setup-1", nowMillis = 200L)

        assertTrue(SelfArmSetupWifiOwnershipStore.clearIfRadioObservedOff(context, wifiEnabled = false))
        assertNull(SelfArmSetupWifiOwnershipStore.read(context))
    }

    @Test
    fun `a stale session cannot claim or discard another sessions record`() {
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context,
            sessionId = "setup-2",
            wifiCurrentlyEnabled = false,
            nowMillis = 100L,
        )

        assertFalse(SelfArmSetupWifiOwnershipStore.markEnableIssued(context, "setup-1"))
        assertFalse(SelfArmSetupWifiOwnershipStore.discardUnissued(context, "setup-1"))
        assertEquals("setup-2", SelfArmSetupWifiOwnershipStore.read(context)?.sessionId)
    }

    @Test
    fun `an in-flight bridge enable blocks restore only for its bounded request window`() {
        SelfArmSetupWifiOwnershipStore.recordBeforeEnable(
            context,
            sessionId = "setup-1",
            wifiCurrentlyEnabled = false,
            nowMillis = 100L,
        )
        SelfArmSetupWifiOwnershipStore.markEnableIssued(
            context,
            sessionId = "setup-1",
            requestInFlight = true,
            nowMillis = 200L,
        )

        assertTrue(SelfArmSetupWifiOwnershipStore.isEnableRequestInFlight(context, nowMillis = 201L))
        assertFalse(SelfArmSetupWifiOwnershipStore.isEnableRequestInFlight(context, nowMillis = 30_200L))
        assertTrue(SelfArmSetupWifiOwnershipStore.markEnableRequestFinished(context, "setup-1"))
        assertFalse(SelfArmSetupWifiOwnershipStore.isEnableRequestInFlight(context, nowMillis = 202L))
    }
}
