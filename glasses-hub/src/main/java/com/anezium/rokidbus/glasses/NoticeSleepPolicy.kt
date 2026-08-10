package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeCloseReason

internal enum class NoticeSleepRefusal(val logValue: String) {
    CLOSE_REASON("close_reason"),
    NO_WAKE_OWNERSHIP("no_wake_ownership"),
    DISPLAY_NOT_INTERACTIVE("not_interactive"),
    LAUNCHER_SHOWN("launcher_shown"),
    SURFACE_ACTIVE("surface_active"),
    ASSISTANT_EPISODE_ACTIVE("assistant_episode_active"),
    ACTIVITY_PRESENTING("activity_presenting"),
    CAMERA_OVERLAY_ACTIVE("camera_overlay_active"),
}

internal sealed interface NoticeSleepDecision {
    data object Sleep : NoticeSleepDecision
    data class Skip(val reason: NoticeSleepRefusal) : NoticeSleepDecision
}

/** Pure close-time policy for returning a notice-owned wake episode to standby. */
internal object NoticeSleepPolicy {
    /** A surface handoff ends notice ownership; an owner replacing its own band does not. */
    fun episodeEnds(closeReason: NoticeCloseReason, surfaceActive: Boolean): Boolean =
        closeReason != NoticeCloseReason.OWNER || surfaceActive

    fun decide(
        closeReason: NoticeCloseReason,
        episodeOwnsWake: Boolean,
        isInteractive: Boolean,
        launcherShown: Boolean,
        surfaceActive: Boolean,
        assistantEpisodeActive: Boolean,
        activityPresenting: Boolean,
        cameraOverlayActive: Boolean,
    ): NoticeSleepDecision = when {
        surfaceActive ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.SURFACE_ACTIVE)
        closeReason != NoticeCloseReason.USER && closeReason != NoticeCloseReason.TIMEOUT ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.CLOSE_REASON)
        assistantEpisodeActive ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.ASSISTANT_EPISODE_ACTIVE)
        !episodeOwnsWake ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.NO_WAKE_OWNERSHIP)
        !isInteractive ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.DISPLAY_NOT_INTERACTIVE)
        launcherShown ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.LAUNCHER_SHOWN)
        activityPresenting ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.ACTIVITY_PRESENTING)
        cameraOverlayActive ->
            NoticeSleepDecision.Skip(NoticeSleepRefusal.CAMERA_OVERLAY_ACTIVE)
        else -> NoticeSleepDecision.Sleep
    }
}
