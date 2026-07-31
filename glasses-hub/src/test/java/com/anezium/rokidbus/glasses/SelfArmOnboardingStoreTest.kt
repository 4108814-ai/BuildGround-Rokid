package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmOnboardingStoreTest {
    @Test
    fun lateCallbackFromOldGenerationCannotFinishNewSession() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)
        val oldSession = SelfArmOnboardingStore.beginSession(context)
        val oldGeneration = SelfArmOnboardingStore.currentGeneration(context)
        val newSession = SelfArmOnboardingStore.beginSession(context)

        SelfArmOnboardingStore.finish(
            context = context,
            sessionId = oldSession,
            setupState = "wireless_bootstrap_complete",
            success = true,
            completionMode = SetupCompletionMode.AUTOMATIC,
        )

        val snapshot = SelfArmOnboardingStore.snapshot(context)
        assertNotEquals(oldSession, newSession)
        assertTrue(SelfArmOnboardingStore.currentGeneration(context) > oldGeneration)
        assertEquals(newSession, snapshot.sessionId)
        assertEquals(SetupStage.WAITING_FOR_ACCESSIBILITY, snapshot.stage)
        assertTrue(SelfArmOnboardingStore.isCurrentSession(context, newSession))
        assertFalse(SelfArmOnboardingStore.isCurrentSession(context, oldSession))
    }

    @Test
    fun runningSnapshotExpiresToRetryableFailure() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markRunning(context, sessionId)
        val fresh = SelfArmOnboardingStore.snapshot(context)
        val expired = SelfArmOnboardingStore.snapshot(
            context,
            nowMillis = System.currentTimeMillis() + SelfArmOnboardingStore.LEASE_TIMEOUT_MS,
        )

        assertTrue(fresh.setupRunning)
        assertFalse(expired.setupRunning)
        assertFalse(expired.leaseValid)
        assertEquals(SetupStage.FAILED, expired.stage)
        assertEquals(SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE, expired.failureState)
        assertEquals(
            SelfArmOnboardingState.Action.RETRY_WIRELESS,
            SelfArmOnboardingStateMachine.evaluate(expired).action,
        )
    }

    @Test
    fun setupOwnedWifiAutomationReportsAWorkingStageBeforeManualFallback() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markRunning(context, sessionId)

        SelfArmOnboardingStore.reportProgress(context, sessionId, "starting_wifi_enable")

        val snapshot = SelfArmOnboardingStore.snapshot(context)
        assertTrue(snapshot.setupRunning)
        assertEquals(SetupStage.ENABLING_WIFI, snapshot.stage)
        assertEquals("starting_wifi_enable", snapshot.progressState)
    }
}
