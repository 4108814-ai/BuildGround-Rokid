package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmOnboardingStateMachineTest {
    @Test
    fun firstLaunchRequiresOnlyTheMainAccessibilityService() {
        val state = evaluate(accessibilityEnabled = false)

        assertEquals(SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY, state.stage)
        assertEquals(SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY, state.action)
    }

    @Test
    fun enabledMainServiceAdvancesToWirelessPairing() {
        val state = evaluate(accessibilityEnabled = true)

        assertEquals(SelfArmOnboardingState.Stage.READY_FOR_WIRELESS, state.stage)
        assertEquals(SelfArmOnboardingState.Action.START_WIRELESS, state.action)
    }

    @Test
    fun runningAndFailedStatesRemainActionable() {
        val running = evaluate(
            accessibilityEnabled = true,
            setupRunning = true,
            progressState = "waiting_for_pairing_code",
        )
        val failed = evaluate(
            accessibilityEnabled = true,
            failureState = "wireless_setup_timeout",
        )

        assertEquals(SelfArmOnboardingState.Stage.RUNNING, running.stage)
        assertEquals("waiting_for_pairing_code", running.detail)
        assertEquals(SelfArmOnboardingState.Stage.FAILED, failed.stage)
        assertEquals(SelfArmOnboardingState.Action.RETRY_WIRELESS, failed.action)
    }

    @Test
    fun failureDiagnosticPassesThroughOnlyForFailedStage() {
        val supportCode = "PAIR-NOPORT"
        val failed = evaluate(
            accessibilityEnabled = true,
            failureState = "pairing_code_expired",
            failureDiagnostic = supportCode,
        )

        assertEquals(SelfArmOnboardingState.Stage.FAILED, failed.stage)
        assertEquals(supportCode, failed.diagnostic)

        val otherStates = listOf(
            evaluate(
                wirelessDebuggingSupported = false,
                accessibilityEnabled = true,
                failureDiagnostic = supportCode,
            ),
            evaluate(accessibilityEnabled = false, failureDiagnostic = supportCode),
            evaluate(accessibilityEnabled = true, failureDiagnostic = supportCode),
            evaluate(
                accessibilityEnabled = true,
                setupRunning = true,
                failureState = "pairing_code_expired",
                failureDiagnostic = supportCode,
            ),
            evaluate(
                accessibilityEnabled = true,
                secureSettingsGranted = true,
                legacyAdbSafe = true,
                failureState = "pairing_code_expired",
                failureDiagnostic = supportCode,
            ),
        )

        assertEquals(
            setOf(
                SelfArmOnboardingState.Stage.UNSUPPORTED,
                SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY,
                SelfArmOnboardingState.Stage.READY_FOR_WIRELESS,
                SelfArmOnboardingState.Stage.RUNNING,
                SelfArmOnboardingState.Stage.COMPLETE,
            ),
            otherStates.map { it.stage }.toSet(),
        )
        otherStates.forEach { assertEquals("", it.diagnostic) }
    }

    @Test
    fun missingWifiNetworkFailureRemainsActionable() {
        val state = evaluate(
            accessibilityEnabled = true,
            failureState = "wifi_network_required",
        )

        assertEquals(SelfArmOnboardingState.Stage.FAILED, state.stage)
        assertEquals(SelfArmOnboardingState.Action.RETRY_WIRELESS, state.action)
        assertEquals("wifi_network_required", state.detail)
    }

    @Test
    fun waitingForWifiAndManualFallbackAreFirstClassOutcomes() {
        val waitingForWifi = evaluate(
            accessibilityEnabled = true,
            stage = SetupStage.WAITING_FOR_WIFI,
            progressState = "waiting_for_wifi_network",
        )
        val manualRequired = evaluate(
            accessibilityEnabled = true,
            stage = SetupStage.MANUAL_REQUIRED,
            failureState = "",
            progressState = "wireless_debugging_manual_step_needed",
        )

        assertEquals(SelfArmOnboardingState.Stage.WAITING_FOR_WIFI, waitingForWifi.stage)
        assertEquals(SelfArmOnboardingState.Action.OPEN_WIFI_PANEL, waitingForWifi.action)
        assertEquals(SelfArmOnboardingState.Stage.MANUAL_REQUIRED, manualRequired.stage)
        assertEquals(SelfArmOnboardingState.Action.OPEN_MANUAL_FALLBACK, manualRequired.action)
    }

    @Test
    fun expiredLeaseProducesRetryableFailure() {
        val state = evaluate(
            accessibilityEnabled = true,
            setupRunning = false,
            leaseValid = false,
            failureState = SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE,
        )

        assertEquals(SelfArmOnboardingState.Stage.FAILED, state.stage)
        assertEquals(SelfArmOnboardingState.Action.RETRY_WIRELESS, state.action)
        assertEquals(SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE, state.detail)
    }

    @Test
    fun leasePolicyExpiresAtTimeoutBoundary() {
        assertTrue(SelfArmSessionPolicy.leaseValid(44_999L, 1L, 45_000L))
        assertFalse(SelfArmSessionPolicy.leaseValid(45_001L, 1L, 45_000L))
    }

    @Test
    fun staleSessionPolicyRejectsLateGenerationCallbacks() {
        assertTrue(SelfArmSessionPolicy.accepts("generation-n", true, "generation-n"))
        assertFalse(SelfArmSessionPolicy.accepts("generation-n-plus-one", true, "generation-n"))
        assertFalse(SelfArmSessionPolicy.accepts("generation-n", false, "generation-n"))
    }

    @Test
    fun partialGrantCannotHideRunningOrFailedSecureBootstrap() {
        val running = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            setupRunning = true,
            progressState = "verifying_legacy_adb_teardown",
        )
        val failed = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            failureState = "legacy_adb_teardown_failed",
        )

        assertEquals(SelfArmOnboardingState.Stage.RUNNING, running.stage)
        assertEquals(SelfArmOnboardingState.Stage.FAILED, failed.stage)
        assertEquals(SelfArmOnboardingState.Action.RETRY_WIRELESS, failed.action)
    }

    @Test
    fun verifiedLivePostureClearsStaleProgressFromPmGrantFallback() {
        val staleRunning = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            legacyAdbSafe = true,
            setupRunning = true,
        )
        val staleFailure = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            legacyAdbSafe = true,
            failureState = "old_wireless_setup_timeout",
        )

        assertEquals(SelfArmOnboardingState.Stage.COMPLETE, staleRunning.stage)
        assertEquals(SelfArmOnboardingState.Stage.COMPLETE, staleFailure.stage)
    }

    @Test
    fun pmGrantFallbackCompletesWithoutWirelessBootstrapMarker() {
        val state = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            bootstrapComplete = false,
            legacyAdbSafe = true,
        )

        assertEquals(SelfArmOnboardingState.Stage.COMPLETE, state.stage)
        assertEquals(SelfArmOnboardingState.Action.NONE, state.action)
    }

    @Test
    fun pmGrantCannotHideUnsafeLegacyAdbPosture() {
        val state = evaluate(
            accessibilityEnabled = true,
            secureSettingsGranted = true,
            bootstrapComplete = false,
            legacyAdbSafe = false,
        )

        assertEquals(SelfArmOnboardingState.Stage.READY_FOR_WIRELESS, state.stage)
        assertEquals(SelfArmOnboardingState.Action.START_WIRELESS, state.action)
    }

    @Test
    fun unsupportedFirmwareStopsBeforeWirelessSetup() {
        val state = evaluate(
            wirelessDebuggingSupported = false,
            accessibilityEnabled = true,
        )

        assertEquals(SelfArmOnboardingState.Stage.UNSUPPORTED, state.stage)
    }

    private fun evaluate(
        wirelessDebuggingSupported: Boolean = true,
        accessibilityEnabled: Boolean,
        secureSettingsGranted: Boolean = false,
        bootstrapComplete: Boolean = false,
        legacyAdbSafe: Boolean = false,
        setupRunning: Boolean = false,
        failureState: String = "",
        failureDiagnostic: String = "",
        progressState: String = "",
        stage: String = SetupStage.UNKNOWN,
        leaseValid: Boolean = true,
    ): SelfArmOnboardingState = SelfArmOnboardingStateMachine.evaluate(
        SelfArmOnboardingSnapshot(
            wirelessDebuggingSupported = wirelessDebuggingSupported,
            accessibilityEnabled = accessibilityEnabled,
            secureSettingsGranted = secureSettingsGranted,
            bootstrapComplete = bootstrapComplete,
            legacyAdbSafe = legacyAdbSafe,
            setupRunning = setupRunning,
            failureState = failureState,
            failureDiagnostic = failureDiagnostic,
            progressState = progressState,
            stage = stage,
            leaseValid = leaseValid,
        ),
    )
}
