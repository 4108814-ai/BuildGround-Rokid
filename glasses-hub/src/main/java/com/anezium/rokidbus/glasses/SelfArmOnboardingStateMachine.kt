package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupCompletionMode
import com.anezium.rokidbus.shared.SetupStage

internal data class SelfArmOnboardingSnapshot(
    val wirelessDebuggingSupported: Boolean,
    val accessibilityEnabled: Boolean,
    val secureSettingsGranted: Boolean,
    val bootstrapComplete: Boolean,
    val legacyAdbSafe: Boolean,
    val setupRunning: Boolean,
    val failureState: String,
    val failureDiagnostic: String,
    val progressState: String,
    val sessionId: String = "",
    val stage: String = SetupStage.UNKNOWN,
    val coreReady: Boolean = false,
    val maintenanceReady: Boolean = false,
    val completionMode: String = SetupCompletionMode.UNKNOWN,
    val leaseValid: Boolean = true,
    val wifiReady: Boolean = false,
)

internal data class SelfArmOnboardingState(
    val stage: Stage,
    val action: Action,
    val detail: String,
    val diagnostic: String = "",
) {
    enum class Stage {
        UNSUPPORTED,
        ENABLE_ACCESSIBILITY,
        READY_FOR_WIRELESS,
        RUNNING,
        WAITING_FOR_WIFI,
        MANUAL_REQUIRED,
        FAILED,
        COMPLETE,
    }

    enum class Action {
        NONE,
        OPEN_ACCESSIBILITY,
        START_WIRELESS,
        RETRY_WIRELESS,
        OPEN_WIFI_PANEL,
        OPEN_MANUAL_FALLBACK,
    }
}

internal object SelfArmOnboardingStateMachine {
    fun evaluate(snapshot: SelfArmOnboardingSnapshot): SelfArmOnboardingState = when {
        snapshot.secureSettingsGranted && snapshot.accessibilityEnabled && snapshot.legacyAdbSafe ->
            state(SelfArmOnboardingState.Stage.COMPLETE)
        !snapshot.leaseValid || snapshot.failureState == SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE ->
            state(
                SelfArmOnboardingState.Stage.FAILED,
                SelfArmOnboardingState.Action.RETRY_WIRELESS,
                SelfArmOnboardingStore.LEASE_EXPIRED_FAILURE,
                snapshot.failureDiagnostic,
            )
        snapshot.setupRunning ->
            state(
                SelfArmOnboardingState.Stage.RUNNING,
                detail = snapshot.progressState,
            )
        snapshot.stage == SetupStage.WAITING_FOR_WIFI ->
            state(
                SelfArmOnboardingState.Stage.WAITING_FOR_WIFI,
                SelfArmOnboardingState.Action.OPEN_WIFI_PANEL,
                snapshot.progressState,
            )
        snapshot.stage == SetupStage.MANUAL_REQUIRED ->
            state(
                SelfArmOnboardingState.Stage.MANUAL_REQUIRED,
                SelfArmOnboardingState.Action.OPEN_MANUAL_FALLBACK,
                snapshot.failureState.ifBlank { snapshot.progressState },
                snapshot.failureDiagnostic,
            )
        snapshot.stage == SetupStage.FAILED ->
            state(
                SelfArmOnboardingState.Stage.FAILED,
                SelfArmOnboardingState.Action.RETRY_WIRELESS,
                snapshot.failureState.ifBlank { snapshot.progressState },
                snapshot.failureDiagnostic,
            )
        snapshot.failureState.isNotBlank() ->
            state(
                SelfArmOnboardingState.Stage.FAILED,
                SelfArmOnboardingState.Action.RETRY_WIRELESS,
                snapshot.failureState,
                snapshot.failureDiagnostic,
            )
        !snapshot.wirelessDebuggingSupported ->
            state(SelfArmOnboardingState.Stage.UNSUPPORTED)
        !snapshot.accessibilityEnabled ->
            state(
                SelfArmOnboardingState.Stage.ENABLE_ACCESSIBILITY,
                SelfArmOnboardingState.Action.OPEN_ACCESSIBILITY,
                snapshot.progressState,
            )
        else ->
            state(
                SelfArmOnboardingState.Stage.READY_FOR_WIRELESS,
                SelfArmOnboardingState.Action.START_WIRELESS,
                if (snapshot.bootstrapComplete) "verifying_wireless_bootstrap" else snapshot.progressState,
            )
    }

    private fun state(
        stage: SelfArmOnboardingState.Stage,
        action: SelfArmOnboardingState.Action = SelfArmOnboardingState.Action.NONE,
        detail: String = "",
        diagnostic: String = "",
    ) = SelfArmOnboardingState(stage, action, detail, diagnostic)
}
