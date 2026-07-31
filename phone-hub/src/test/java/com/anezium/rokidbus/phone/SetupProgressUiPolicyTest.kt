package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.SetupStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProgressUiPolicyTest {
    @Test
    fun `a live setup session always owns a visible phone surface`() {
        assertTrue(
            SetupProgressUiPolicy.isVisible(
                setupComplete = false,
                glassesAppInstalled = true,
                sessionId = "0123456789abcdef",
                stage = SetupStage.ENABLING_WIFI,
                running = true,
                handoffActive = false,
            ),
        )
    }

    @Test
    fun `handoff and manual states remain visible with or without a running flag`() {
        assertTrue(
            SetupProgressUiPolicy.isVisible(false, true, "", "", false, true),
        )
        assertTrue(SetupProgressUiPolicy.needsAttention(SetupStage.MANUAL_REQUIRED, false, false))
        assertTrue(SetupProgressUiPolicy.needsAttention(SetupStage.FAILED, false, false))
        assertTrue(SetupProgressUiPolicy.showsSupportCode(SetupStage.MANUAL_REQUIRED, false))
        assertTrue(SetupProgressUiPolicy.showsSupportCode(SetupStage.FAILED, false))
    }

    @Test
    fun `successful setup removes only the progress surface`() {
        assertFalse(
            SetupProgressUiPolicy.isVisible(
                setupComplete = true,
                glassesAppInstalled = true,
                sessionId = "0123456789abcdef",
                stage = SetupStage.COMPLETE,
                running = false,
                handoffActive = false,
            ),
        )
    }
}
