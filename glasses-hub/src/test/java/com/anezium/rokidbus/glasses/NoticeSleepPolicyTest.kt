package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeCloseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSleepPolicyTest {
    @Test
    fun `user and timeout closes sleep when the notice owns the only visible wake`() {
        assertEquals(NoticeSleepDecision.Sleep, decide(NoticeCloseReason.USER))
        assertEquals(NoticeSleepDecision.Sleep, decide(NoticeCloseReason.TIMEOUT))
    }

    @Test
    fun `owner and replaced closes never sleep`() {
        assertSkipped(NoticeSleepRefusal.CLOSE_REASON, decide(NoticeCloseReason.OWNER))
        assertSkipped(NoticeSleepRefusal.CLOSE_REASON, decide(NoticeCloseReason.REPLACED))
    }

    @Test
    fun `no wake ownership blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.NO_WAKE_OWNERSHIP,
            decide(NoticeCloseReason.USER, episodeOwnsWake = false),
        )
    }

    @Test
    fun `already dark display blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.DISPLAY_NOT_INTERACTIVE,
            decide(NoticeCloseReason.USER, isInteractive = false),
        )
    }

    @Test
    fun `launcher blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.LAUNCHER_SHOWN,
            decide(NoticeCloseReason.USER, launcherShown = true),
        )
    }

    @Test
    fun `surface blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.SURFACE_ACTIVE,
            decide(NoticeCloseReason.USER, surfaceActive = true),
        )
    }

    @Test
    fun `surface handoff blocks sleep even without notice wake ownership`() {
        assertSkipped(
            NoticeSleepRefusal.SURFACE_ACTIVE,
            decide(
                NoticeCloseReason.OWNER,
                episodeOwnsWake = false,
                surfaceActive = true,
            ),
        )
        assertSkipped(
            NoticeSleepRefusal.SURFACE_ACTIVE,
            decide(
                NoticeCloseReason.TIMEOUT,
                episodeOwnsWake = false,
                surfaceActive = true,
            ),
        )
    }

    @Test
    fun `assistant episode blocks timeout sleep while waiting for its card`() {
        assertSkipped(
            NoticeSleepRefusal.ASSISTANT_EPISODE_ACTIVE,
            decide(
                NoticeCloseReason.TIMEOUT,
                assistantEpisodeActive = true,
            ),
        )
    }

    @Test
    fun `owner hide ends a notice wake episode only when a surface takes over`() {
        assertFalse(
            NoticeSleepPolicy.episodeEnds(
                NoticeCloseReason.OWNER,
                surfaceActive = false,
            ),
        )
        assertTrue(
            NoticeSleepPolicy.episodeEnds(
                NoticeCloseReason.OWNER,
                surfaceActive = true,
            ),
        )
        assertTrue(
            NoticeSleepPolicy.episodeEnds(
                NoticeCloseReason.USER,
                surfaceActive = false,
            ),
        )
    }

    @Test
    fun `activity tier blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.ACTIVITY_PRESENTING,
            decide(NoticeCloseReason.USER, activityPresenting = true),
        )
    }

    @Test
    fun `camera overlay blocks sleep`() {
        assertSkipped(
            NoticeSleepRefusal.CAMERA_OVERLAY_ACTIVE,
            decide(NoticeCloseReason.USER, cameraOverlayActive = true),
        )
    }

    private fun decide(
        closeReason: NoticeCloseReason,
        episodeOwnsWake: Boolean = true,
        isInteractive: Boolean = true,
        launcherShown: Boolean = false,
        surfaceActive: Boolean = false,
        assistantEpisodeActive: Boolean = false,
        activityPresenting: Boolean = false,
        cameraOverlayActive: Boolean = false,
    ): NoticeSleepDecision = NoticeSleepPolicy.decide(
        closeReason = closeReason,
        episodeOwnsWake = episodeOwnsWake,
        isInteractive = isInteractive,
        launcherShown = launcherShown,
        surfaceActive = surfaceActive,
        assistantEpisodeActive = assistantEpisodeActive,
        activityPresenting = activityPresenting,
        cameraOverlayActive = cameraOverlayActive,
    )

    private fun assertSkipped(
        reason: NoticeSleepRefusal,
        decision: NoticeSleepDecision,
    ) {
        assertEquals(NoticeSleepDecision.Skip(reason), decision)
    }
}
