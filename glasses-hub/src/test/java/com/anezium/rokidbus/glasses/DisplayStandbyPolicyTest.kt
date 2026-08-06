package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayStandbyPolicyTest {
    @Test
    fun `every gate combination sleeps only for the two known idle windows`() {
        var combinations = 0
        StandbyInteractiveState.entries.forEach { interactive ->
            StandbyChargingState.entries.forEach { charging ->
                StandbyTopWindow.entries.forEach { topWindow ->
                    repeat(1 shl BOOLEAN_GATE_COUNT) { mask ->
                        val conditions = conditionsFromMask(
                            mask = mask,
                            interactive = interactive,
                            charging = charging,
                            topWindow = topWindow,
                        )
                        listOf(IDLE_WINDOW_MS - 1L, IDLE_WINDOW_MS).forEach { idleDuration ->
                            val decision = decide(
                                conditions = conditions,
                                state = DisplayStandbyPolicyState(eligibleSinceMs = 0L),
                                nowMs = idleDuration,
                                lastUserInputAtMs = 0L,
                            ).decision
                            val shouldSleep =
                                interactive == StandbyInteractiveState.INTERACTIVE &&
                                    charging == StandbyChargingState.ON_BATTERY &&
                                    topWindow in IDLE_TOP_WINDOWS &&
                                    mask == 0 &&
                                    idleDuration >= IDLE_WINDOW_MS
                            assertEquals(
                                "interactive=$interactive charging=$charging top=$topWindow " +
                                    "mask=$mask idle=$idleDuration",
                                shouldSleep,
                                decision is DisplayStandbyDecision.Sleep,
                            )
                            combinations++
                        }
                    }
                }
            }
        }
        assertEquals(73_728, combinations)
    }

    @Test
    fun `every refusal gate reports its decision-table reason`() {
        assertBlocked(
            DisplayStandbyRefusal.DISPLAY_STATE_UNKNOWN,
            conditions(interactiveState = StandbyInteractiveState.UNKNOWN),
        )
        assertBlocked(
            DisplayStandbyRefusal.DISPLAY_NOT_INTERACTIVE,
            conditions(interactiveState = StandbyInteractiveState.NOT_INTERACTIVE),
        )
        assertBlocked(
            DisplayStandbyRefusal.CHARGING_STATE_UNKNOWN,
            conditions(chargingState = StandbyChargingState.UNKNOWN),
        )
        assertBlocked(
            DisplayStandbyRefusal.CHARGING,
            conditions(chargingState = StandbyChargingState.CHARGING),
        )
        assertBlocked(DisplayStandbyRefusal.SURFACE_PRESENTING, conditions(surfacePresenting = true))
        assertBlocked(DisplayStandbyRefusal.NOTICE_PRESENTING, conditions(noticePresenting = true))
        assertBlocked(DisplayStandbyRefusal.ACTIVITY_PRESENTING, conditions(activityPresenting = true))
        assertBlocked(
            DisplayStandbyRefusal.CAMERA_SESSION_ACTIVE,
            conditions(cameraSessionActive = true),
        )
        assertBlocked(
            DisplayStandbyRefusal.LAUNCHER_OVERLAY_SHOWN,
            conditions(launcherOverlayShown = true),
        )
        assertBlocked(
            DisplayStandbyRefusal.MAIN_ACTIVITY_INTERACTIVE,
            conditions(mainActivityInteractiveFlow = true),
        )
        assertBlocked(
            DisplayStandbyRefusal.TOP_WINDOW_UNKNOWN,
            conditions(topWindow = StandbyTopWindow.UNKNOWN),
        )
        assertBlocked(
            DisplayStandbyRefusal.NON_IDLE_TOP_WINDOW,
            conditions(topWindow = StandbyTopWindow.OTHER),
        )
        assertBlocked(
            DisplayStandbyRefusal.SPEECH_CAPTURE_ACTIVE,
            conditions(speechCaptureActive = true),
        )
        assertBlocked(
            DisplayStandbyRefusal.TTS_UTTERANCE_ACTIVE,
            conditions(ttsUtteranceActive = true),
        )
        assertBlocked(DisplayStandbyRefusal.SETUP_FLOW_ACTIVE, conditions(setupFlowActive = true))
        assertBlocked(DisplayStandbyRefusal.MEDIA_SYNC_ACTIVE, conditions(mediaSyncActive = true))
    }

    @Test
    fun `idle window begins at the first fully eligible observation`() {
        val first = decide(
            conditions = conditions(),
            state = DisplayStandbyPolicyState(),
            nowMs = 10_000L,
            lastUserInputAtMs = 1_000L,
        )
        assertEquals(
            DisplayStandbyDecision.Waiting(0L, IDLE_WINDOW_MS),
            first.decision,
        )

        val almost = decide(
            conditions = conditions(),
            state = first.nextState,
            nowMs = 10_000L + IDLE_WINDOW_MS - 1L,
            lastUserInputAtMs = 1_000L,
        )
        assertEquals(
            DisplayStandbyDecision.Waiting(IDLE_WINDOW_MS - 1L, 1L),
            almost.decision,
        )

        val ready = decide(
            conditions = conditions(topWindow = StandbyTopWindow.NEXUS_MAIN),
            state = almost.nextState,
            nowMs = 10_000L + IDLE_WINDOW_MS,
            lastUserInputAtMs = 1_000L,
        )
        assertEquals(
            DisplayStandbyDecision.Sleep(IDLE_WINDOW_MS, StandbyTopWindow.NEXUS_MAIN),
            ready.decision,
        )
    }

    @Test
    fun `user input restarts the continuous idle window at its event timestamp`() {
        val result = decide(
            conditions = conditions(),
            state = DisplayStandbyPolicyState(eligibleSinceMs = 10_000L),
            nowMs = 30_000L,
            lastUserInputAtMs = 25_000L,
        )

        assertEquals(
            DisplayStandbyDecision.Waiting(5_000L, IDLE_WINDOW_MS - 5_000L),
            result.decision,
        )
        assertEquals(25_000L, result.nextState.eligibleSinceMs)
    }

    @Test
    fun `a blocked observation clears previously accumulated idle time`() {
        val blocked = decide(
            conditions = conditions(surfacePresenting = true),
            state = DisplayStandbyPolicyState(eligibleSinceMs = 1_000L),
            nowMs = IDLE_WINDOW_MS,
            lastUserInputAtMs = 1_000L,
        )
        assertEquals(DisplayStandbyPolicyState(), blocked.nextState)

        val eligibleAgain = decide(
            conditions = conditions(),
            state = blocked.nextState,
            nowMs = IDLE_WINDOW_MS + 10_000L,
            lastUserInputAtMs = 1_000L,
        )
        assertEquals(
            DisplayStandbyDecision.Waiting(0L, IDLE_WINDOW_MS),
            eligibleAgain.decision,
        )
    }

    @Test
    fun `future input timestamp cannot manufacture negative idle time`() {
        val result = decide(
            conditions = conditions(),
            state = DisplayStandbyPolicyState(eligibleSinceMs = 1_000L),
            nowMs = 5_000L,
            lastUserInputAtMs = 9_000L,
        )

        assertEquals(DisplayStandbyDecision.Waiting(0L, IDLE_WINDOW_MS), result.decision)
        assertEquals(5_000L, result.nextState.eligibleSinceMs)
    }

    private fun assertBlocked(
        reason: DisplayStandbyRefusal,
        conditions: DisplayStandbyConditions,
    ) {
        val evaluation = decide(
            conditions = conditions,
            state = DisplayStandbyPolicyState(eligibleSinceMs = 0L),
            nowMs = IDLE_WINDOW_MS,
            lastUserInputAtMs = 0L,
        )
        assertEquals(DisplayStandbyDecision.Blocked(reason), evaluation.decision)
        assertEquals(DisplayStandbyPolicyState(), evaluation.nextState)
    }

    private fun conditionsFromMask(
        mask: Int,
        interactive: StandbyInteractiveState,
        charging: StandbyChargingState,
        topWindow: StandbyTopWindow,
    ): DisplayStandbyConditions = conditions(
        interactiveState = interactive,
        chargingState = charging,
        surfacePresenting = mask hasGate 0,
        noticePresenting = mask hasGate 1,
        activityPresenting = mask hasGate 2,
        cameraSessionActive = mask hasGate 3,
        launcherOverlayShown = mask hasGate 4,
        mainActivityInteractiveFlow = mask hasGate 5,
        speechCaptureActive = mask hasGate 6,
        ttsUtteranceActive = mask hasGate 7,
        setupFlowActive = mask hasGate 8,
        mediaSyncActive = mask hasGate 9,
        topWindow = topWindow,
    )

    private infix fun Int.hasGate(bit: Int): Boolean = this and (1 shl bit) != 0

    private fun decide(
        conditions: DisplayStandbyConditions,
        state: DisplayStandbyPolicyState,
        nowMs: Long,
        lastUserInputAtMs: Long,
    ): DisplayStandbyEvaluation = DisplayStandbyPolicy.decide(
        conditions = conditions,
        state = state,
        nowMs = nowMs,
        lastUserInputAtMs = lastUserInputAtMs,
        idleWindowMs = IDLE_WINDOW_MS,
    )

    private fun conditions(
        interactiveState: StandbyInteractiveState = StandbyInteractiveState.INTERACTIVE,
        chargingState: StandbyChargingState = StandbyChargingState.ON_BATTERY,
        surfacePresenting: Boolean = false,
        noticePresenting: Boolean = false,
        activityPresenting: Boolean = false,
        cameraSessionActive: Boolean = false,
        launcherOverlayShown: Boolean = false,
        mainActivityInteractiveFlow: Boolean = false,
        topWindow: StandbyTopWindow = StandbyTopWindow.ROM_HOME,
        speechCaptureActive: Boolean = false,
        ttsUtteranceActive: Boolean = false,
        setupFlowActive: Boolean = false,
        mediaSyncActive: Boolean = false,
    ): DisplayStandbyConditions = DisplayStandbyConditions(
        interactiveState = interactiveState,
        chargingState = chargingState,
        surfacePresenting = surfacePresenting,
        noticePresenting = noticePresenting,
        activityPresenting = activityPresenting,
        cameraSessionActive = cameraSessionActive,
        launcherOverlayShown = launcherOverlayShown,
        mainActivityInteractiveFlow = mainActivityInteractiveFlow,
        topWindow = topWindow,
        speechCaptureActive = speechCaptureActive,
        ttsUtteranceActive = ttsUtteranceActive,
        setupFlowActive = setupFlowActive,
        mediaSyncActive = mediaSyncActive,
    )

    private companion object {
        const val BOOLEAN_GATE_COUNT = 10
        const val IDLE_WINDOW_MS = 180_000L
        val IDLE_TOP_WINDOWS = setOf(StandbyTopWindow.ROM_HOME, StandbyTopWindow.NEXUS_MAIN)
    }
}
