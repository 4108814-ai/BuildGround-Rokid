package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayListenerRepairPolicyTest {
    private val policy = RelayListenerRepairPolicy()

    @Test
    fun `revoked access never requests listener work`() {
        val decision = evaluate(access = false)

        assertEquals(RelayRepairAction.NONE, decision.action)
        assertEquals(RelayRepairResult.NO_ACCESS, decision.result)
        assertEquals(RelayRepairPhase.IDLE, decision.state.phase)
        assertEquals(null, decision.nextEvaluationDelayMs)
    }

    @Test
    fun `bare rebind waits fifteen seconds before static clean cycle on API 34`() {
        val first = evaluate(now = 1_000L)
        val waiting = evaluate(first.state, now = 15_999L)
        val clean = evaluate(first.state, now = 16_000L)

        assertEquals(RelayRepairAction.REQUEST_REBIND, first.action)
        assertEquals(15_000L, first.nextEvaluationDelayMs ?: -1L)
        assertEquals(RelayRepairAction.NONE, waiting.action)
        assertEquals(RelayRepairResult.WAITING, waiting.result)
        assertEquals(RelayRepairAction.REQUEST_STATIC_UNBIND_REBIND, clean.action)
        assertEquals(RelayRepairResult.CLEAN_STATIC_REQUESTED, clean.result)
    }

    @Test
    fun `API 30 through 33 uses live instance unbind or repeats rebind`() {
        val first = evaluate(now = 0L, api = 33)

        val withInstance = evaluate(first.state, now = 15_000L, api = 33, liveInstance = true)
        val withoutInstance = evaluate(first.state, now = 15_000L, api = 33, liveInstance = false)

        assertEquals(RelayRepairAction.REQUEST_INSTANCE_UNBIND_REBIND, withInstance.action)
        assertEquals(RelayRepairAction.REQUEST_REBIND_AGAIN, withoutInstance.action)
    }

    @Test
    fun `new generation completes a pending repair when listener stays connected`() {
        val first = evaluate(now = 10L, generation = 4L)

        val connected = evaluate(
            first.state,
            now = 100L,
            generation = 5L,
            connected = true,
        )

        assertEquals(RelayRepairAction.NONE, connected.action)
        assertEquals(RelayRepairResult.CONNECTED, connected.result)
        assertEquals(0, connected.state.failureCount)
        assertEquals(RelayRepairPhase.IDLE, connected.state.phase)
    }

    @Test
    fun `transient generation advance while disconnected starts a fresh bare rebind`() {
        val first = evaluate(now = 10L, generation = 4L)

        val disconnectedAgain = evaluate(
            first.state,
            now = 100L,
            generation = 5L,
            connected = false,
        )

        assertEquals(RelayRepairAction.REQUEST_REBIND, disconnectedAgain.action)
        assertEquals(RelayRepairResult.REBIND_REQUESTED, disconnectedAgain.result)
        assertEquals(5L, disconnectedAgain.state.expectedGeneration)
    }

    @Test
    fun `failed repairs back off one then five then fifteen minutes`() {
        val first = evaluate(now = 0L)
        val clean = evaluate(first.state, now = 15_000L)
        val failedOnce = evaluate(clean.state, now = 30_000L)
        assertEquals(60_000L, failedOnce.nextEvaluationDelayMs ?: -1L)

        val secondRebind = evaluate(failedOnce.state, now = 90_000L)
        val failedTwice = evaluate(secondRebind.state, now = 105_000L)
        assertEquals(RelayRepairResult.CLEAN_RATE_LIMITED, failedTwice.result)
        assertEquals(5L * 60L * 1_000L, failedTwice.nextEvaluationDelayMs ?: -1L)

        val thirdRebind = evaluate(failedTwice.state, now = 405_000L)
        val failedThreeTimes = evaluate(thirdRebind.state, now = 420_000L)
        assertEquals(15L * 60L * 1_000L, failedThreeTimes.nextEvaluationDelayMs ?: -1L)

        val fourthRebind = evaluate(failedThreeTimes.state, now = 1_320_000L)
        val nextClean = evaluate(fourthRebind.state, now = 1_335_000L)
        assertEquals(RelayRepairAction.REQUEST_STATIC_UNBIND_REBIND, nextClean.action)
    }

    @Test
    fun `clean cycle remains rate limited while bare rebind retries continue`() {
        val first = evaluate(now = 0L)
        val clean = evaluate(first.state, now = 15_000L)
        val failed = evaluate(clean.state, now = 30_000L)
        val retry = evaluate(failed.state, now = 90_000L)
        val rateLimited = evaluate(retry.state, now = 105_000L)

        assertEquals(RelayRepairAction.REQUEST_REBIND, retry.action)
        assertEquals(RelayRepairResult.CLEAN_RATE_LIMITED, rateLimited.result)
        assertTrue(rateLimited.state.nextAttemptUptimeMs < clean.state.lastCleanUptimeMs + 15L * 60L * 1_000L)
    }

    @Test
    fun `failure backoff remains capped at fifteen minutes`() {
        val waiting = RelayRepairState(
            phase = RelayRepairPhase.WAITING_FOR_CLEAN_REBIND,
            expectedGeneration = 2L,
            deadlineUptimeMs = 100L,
            failureCount = 100,
            lastCleanUptimeMs = 50L,
            lastObservedUptimeMs = 99L,
        )

        val failed = evaluate(waiting, now = 100L, generation = 2L)

        assertEquals(RelayRepairResult.FAILED, failed.result)
        assertEquals(15L * 60L * 1_000L, failed.nextEvaluationDelayMs ?: -1L)
    }

    @Test
    fun `uptime rollback clears persisted deadlines after reboot`() {
        val stale = RelayRepairState(
            phase = RelayRepairPhase.WAITING_FOR_CLEAN_REBIND,
            expectedGeneration = 7L,
            deadlineUptimeMs = 9_000_000L,
            failureCount = 3,
            nextAttemptUptimeMs = 9_000_000L,
            lastCleanUptimeMs = 8_000_000L,
            lastObservedUptimeMs = 8_100_000L,
        )

        val restarted = evaluate(stale, now = 1_000L, generation = 7L)

        assertEquals(RelayRepairAction.REQUEST_REBIND, restarted.action)
        assertEquals(0, restarted.state.failureCount)
        assertEquals(1_000L, restarted.state.lastObservedUptimeMs)
    }

    private fun evaluate(
        state: RelayRepairState = RelayRepairState(),
        now: Long = 0L,
        access: Boolean = true,
        connected: Boolean = false,
        generation: Long = 0L,
        api: Int = 34,
        liveInstance: Boolean = false,
    ): RelayRepairDecision = policy.evaluate(
        state,
        RelayRepairInput(
            nowUptimeMs = now,
            accessGranted = access,
            listenerConnected = connected,
            connectGeneration = generation,
            apiLevel = api,
            hasLiveListenerInstance = liveInstance,
        ),
    )
}
