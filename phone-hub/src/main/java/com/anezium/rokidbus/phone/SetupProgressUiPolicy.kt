package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.SetupStage

internal object SetupProgressUiPolicy {
    fun isVisible(
        setupComplete: Boolean,
        glassesAppInstalled: Boolean,
        sessionId: String,
        stage: String,
        running: Boolean,
        handoffActive: Boolean,
    ): Boolean =
        !setupComplete &&
            (
                handoffActive ||
                    glassesAppInstalled &&
                    (sessionId.isNotBlank() || SetupStage.normalize(stage).isNotBlank() || running)
                )

    fun needsAttention(stage: String, requiresUserAction: Boolean, handoffFailed: Boolean): Boolean =
        handoffFailed || requiresUserAction || SetupStage.normalize(stage) in setOf(
            SetupStage.WAITING_FOR_ACCESSIBILITY,
            SetupStage.WAITING_FOR_WIFI,
            SetupStage.MANUAL_REQUIRED,
            SetupStage.FAILED,
        )

    fun showsSupportCode(stage: String, handoffFailed: Boolean): Boolean =
        handoffFailed || SetupStage.normalize(stage) in setOf(
            SetupStage.WAITING_FOR_WIFI,
            SetupStage.MANUAL_REQUIRED,
            SetupStage.FAILED,
        )
}
