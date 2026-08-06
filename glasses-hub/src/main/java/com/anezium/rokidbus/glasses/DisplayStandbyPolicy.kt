package com.anezium.rokidbus.glasses

internal enum class StandbyInteractiveState {
    UNKNOWN,
    NOT_INTERACTIVE,
    INTERACTIVE,
}

internal enum class StandbyChargingState {
    UNKNOWN,
    CHARGING,
    ON_BATTERY,
}

internal enum class StandbyTopWindow(val logValue: String) {
    UNKNOWN("unknown"),
    ROM_HOME("rom_home"),
    NEXUS_MAIN("nexus_main"),
    OTHER("other"),
}

internal data class DisplayStandbyConditions(
    val interactiveState: StandbyInteractiveState,
    val chargingState: StandbyChargingState,
    val surfacePresenting: Boolean,
    val noticePresenting: Boolean,
    val activityPresenting: Boolean,
    val cameraSessionActive: Boolean,
    val launcherOverlayShown: Boolean,
    val mainActivityInteractiveFlow: Boolean,
    val topWindow: StandbyTopWindow,
    val speechCaptureActive: Boolean,
    val ttsUtteranceActive: Boolean,
    val setupFlowActive: Boolean,
    val mediaSyncActive: Boolean,
)

internal data class DisplayStandbyPolicyState(
    val eligibleSinceMs: Long? = null,
)

internal enum class DisplayStandbyRefusal(val logValue: String) {
    DISPLAY_STATE_UNKNOWN("display_state_unknown"),
    DISPLAY_NOT_INTERACTIVE("display_not_interactive"),
    CHARGING_STATE_UNKNOWN("charging_state_unknown"),
    CHARGING("charging"),
    SURFACE_PRESENTING("surface_presenting"),
    NOTICE_PRESENTING("notice_presenting"),
    ACTIVITY_PRESENTING("activity_presenting"),
    CAMERA_SESSION_ACTIVE("camera_session_active"),
    LAUNCHER_OVERLAY_SHOWN("launcher_overlay_shown"),
    MAIN_ACTIVITY_INTERACTIVE("main_activity_interactive"),
    TOP_WINDOW_UNKNOWN("top_window_unknown"),
    NON_IDLE_TOP_WINDOW("non_idle_top_window"),
    SPEECH_CAPTURE_ACTIVE("speech_capture_active"),
    TTS_UTTERANCE_ACTIVE("tts_utterance_active"),
    SETUP_FLOW_ACTIVE("setup_flow_active"),
    MEDIA_SYNC_ACTIVE("media_sync_active"),
}

internal sealed interface DisplayStandbyDecision {
    data class Blocked(val reason: DisplayStandbyRefusal) : DisplayStandbyDecision
    data class Waiting(val idleDurationMs: Long, val remainingMs: Long) : DisplayStandbyDecision
    data class Sleep(val idleDurationMs: Long, val topWindow: StandbyTopWindow) : DisplayStandbyDecision
}

internal data class DisplayStandbyEvaluation(
    val decision: DisplayStandbyDecision,
    val nextState: DisplayStandbyPolicyState,
)

/** Pure gate and continuous-idle policy for the display standby watchdog. */
internal object DisplayStandbyPolicy {
    const val DEFAULT_IDLE_WINDOW_MS = 3 * 60_000L

    fun decide(
        conditions: DisplayStandbyConditions,
        state: DisplayStandbyPolicyState,
        nowMs: Long,
        lastUserInputAtMs: Long,
        idleWindowMs: Long = DEFAULT_IDLE_WINDOW_MS,
    ): DisplayStandbyEvaluation {
        blocker(conditions)?.let { reason ->
            return DisplayStandbyEvaluation(
                DisplayStandbyDecision.Blocked(reason),
                DisplayStandbyPolicyState(),
            )
        }

        val observedEligibleSince = state.eligibleSinceMs ?: nowMs
        val inputBound = lastUserInputAtMs.coerceAtMost(nowMs)
        val eligibleSince = maxOf(observedEligibleSince, inputBound)
        val idleDuration = (nowMs - eligibleSince).coerceAtLeast(0L)
        val nextState = DisplayStandbyPolicyState(eligibleSince)
        return if (idleDuration >= idleWindowMs) {
            DisplayStandbyEvaluation(
                DisplayStandbyDecision.Sleep(idleDuration, conditions.topWindow),
                nextState,
            )
        } else {
            DisplayStandbyEvaluation(
                DisplayStandbyDecision.Waiting(idleDuration, idleWindowMs - idleDuration),
                nextState,
            )
        }
    }

    private fun blocker(conditions: DisplayStandbyConditions): DisplayStandbyRefusal? = when {
        conditions.interactiveState == StandbyInteractiveState.UNKNOWN ->
            DisplayStandbyRefusal.DISPLAY_STATE_UNKNOWN
        conditions.interactiveState != StandbyInteractiveState.INTERACTIVE ->
            DisplayStandbyRefusal.DISPLAY_NOT_INTERACTIVE
        conditions.chargingState == StandbyChargingState.UNKNOWN ->
            DisplayStandbyRefusal.CHARGING_STATE_UNKNOWN
        conditions.chargingState == StandbyChargingState.CHARGING ->
            DisplayStandbyRefusal.CHARGING
        conditions.surfacePresenting -> DisplayStandbyRefusal.SURFACE_PRESENTING
        conditions.noticePresenting -> DisplayStandbyRefusal.NOTICE_PRESENTING
        conditions.activityPresenting -> DisplayStandbyRefusal.ACTIVITY_PRESENTING
        conditions.cameraSessionActive -> DisplayStandbyRefusal.CAMERA_SESSION_ACTIVE
        conditions.launcherOverlayShown -> DisplayStandbyRefusal.LAUNCHER_OVERLAY_SHOWN
        conditions.mainActivityInteractiveFlow -> DisplayStandbyRefusal.MAIN_ACTIVITY_INTERACTIVE
        conditions.topWindow == StandbyTopWindow.UNKNOWN -> DisplayStandbyRefusal.TOP_WINDOW_UNKNOWN
        conditions.topWindow != StandbyTopWindow.ROM_HOME &&
            conditions.topWindow != StandbyTopWindow.NEXUS_MAIN ->
            DisplayStandbyRefusal.NON_IDLE_TOP_WINDOW
        conditions.speechCaptureActive -> DisplayStandbyRefusal.SPEECH_CAPTURE_ACTIVE
        conditions.ttsUtteranceActive -> DisplayStandbyRefusal.TTS_UTTERANCE_ACTIVE
        conditions.setupFlowActive -> DisplayStandbyRefusal.SETUP_FLOW_ACTIVE
        conditions.mediaSyncActive -> DisplayStandbyRefusal.MEDIA_SYNC_ACTIVE
        else -> null
    }
}
