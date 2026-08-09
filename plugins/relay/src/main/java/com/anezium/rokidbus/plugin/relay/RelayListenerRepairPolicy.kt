package com.anezium.rokidbus.plugin.relay

internal enum class RelayRepairPhase {
    IDLE,
    WAITING_FOR_REBIND,
    WAITING_FOR_CLEAN_REBIND,
}

internal enum class RelayRepairAction {
    NONE,
    REQUEST_REBIND,
    REQUEST_STATIC_UNBIND_REBIND,
    REQUEST_INSTANCE_UNBIND_REBIND,
    REQUEST_REBIND_AGAIN,
}

internal data class RelayRepairState(
    val phase: RelayRepairPhase = RelayRepairPhase.IDLE,
    val expectedGeneration: Long = 0L,
    val deadlineUptimeMs: Long = 0L,
    val failureCount: Int = 0,
    val nextAttemptUptimeMs: Long = 0L,
    val lastCleanUptimeMs: Long = NO_UPTIME,
    val lastObservedUptimeMs: Long = 0L,
) {
    companion object {
        const val NO_UPTIME = -1L
    }
}

internal data class RelayRepairInput(
    val nowUptimeMs: Long,
    val accessGranted: Boolean,
    val listenerConnected: Boolean,
    val connectGeneration: Long,
    val apiLevel: Int,
    val hasLiveListenerInstance: Boolean,
)

internal data class RelayRepairDecision(
    val action: RelayRepairAction,
    val result: RelayRepairResult,
    val state: RelayRepairState,
    val nextEvaluationDelayMs: Long? = null,
)

internal class RelayListenerRepairPolicy(
    private val reconnectWaitMs: Long = RECONNECT_WAIT_MS,
    private val cleanRateLimitMs: Long = CLEAN_RATE_LIMIT_MS,
    private val failureBackoffMs: LongArray = FAILURE_BACKOFF_MS,
) {
    init {
        require(reconnectWaitMs > 0L)
        require(cleanRateLimitMs > 0L)
        require(failureBackoffMs.isNotEmpty() && failureBackoffMs.all { it > 0L })
    }

    fun evaluate(current: RelayRepairState, input: RelayRepairInput): RelayRepairDecision {
        val now = input.nowUptimeMs.coerceAtLeast(0L)
        val state = normalizeClock(current, now)

        if (!input.accessGranted) {
            return stamp(
                RelayRepairDecision(
                    RelayRepairAction.NONE,
                    RelayRepairResult.NO_ACCESS,
                    state.copy(
                        phase = RelayRepairPhase.IDLE,
                        expectedGeneration = input.connectGeneration,
                        deadlineUptimeMs = 0L,
                        failureCount = 0,
                        nextAttemptUptimeMs = 0L,
                    ),
                ),
                now,
            )
        }

        if (input.listenerConnected) return stamp(connected(state, input.connectGeneration), now)
        if (state.phase != RelayRepairPhase.IDLE && input.connectGeneration != state.expectedGeneration) {
            val next = state.copy(
                phase = RelayRepairPhase.IDLE,
                expectedGeneration = input.connectGeneration,
                deadlineUptimeMs = 0L,
                failureCount = 0,
                nextAttemptUptimeMs = 0L,
            )
            return stamp(evaluateIdle(next, input, now), now)
        }

        val decision = when (state.phase) {
            RelayRepairPhase.WAITING_FOR_REBIND -> evaluateRebindWait(state, input, now)
            RelayRepairPhase.WAITING_FOR_CLEAN_REBIND -> evaluateCleanWait(state, input, now)
            RelayRepairPhase.IDLE -> evaluateIdle(state, input, now)
        }
        return stamp(decision, now)
    }

    private fun evaluateIdle(
        state: RelayRepairState,
        input: RelayRepairInput,
        now: Long,
    ): RelayRepairDecision {
        if (now < state.nextAttemptUptimeMs) {
            return RelayRepairDecision(
                RelayRepairAction.NONE,
                RelayRepairResult.BACKING_OFF,
                state,
                state.nextAttemptUptimeMs - now,
            )
        }
        return RelayRepairDecision(
            RelayRepairAction.REQUEST_REBIND,
            RelayRepairResult.REBIND_REQUESTED,
            state.copy(
                phase = RelayRepairPhase.WAITING_FOR_REBIND,
                expectedGeneration = input.connectGeneration,
                deadlineUptimeMs = deadlineAfter(now, reconnectWaitMs),
                nextAttemptUptimeMs = 0L,
            ),
            reconnectWaitMs,
        )
    }

    private fun evaluateRebindWait(
        state: RelayRepairState,
        input: RelayRepairInput,
        now: Long,
    ): RelayRepairDecision {
        if (now < state.deadlineUptimeMs) {
            return RelayRepairDecision(
                RelayRepairAction.NONE,
                RelayRepairResult.WAITING,
                state,
                state.deadlineUptimeMs - now,
            )
        }

        val cleanAllowedAt = if (state.lastCleanUptimeMs == RelayRepairState.NO_UPTIME) {
            now
        } else {
            deadlineAfter(state.lastCleanUptimeMs, cleanRateLimitMs)
        }
        if (now < cleanAllowedAt) {
            return failedWithoutClean(state, input.connectGeneration, now)
        }

        val action = when {
            input.apiLevel >= STATIC_REQUEST_UNBIND_API -> RelayRepairAction.REQUEST_STATIC_UNBIND_REBIND
            input.hasLiveListenerInstance -> RelayRepairAction.REQUEST_INSTANCE_UNBIND_REBIND
            else -> RelayRepairAction.REQUEST_REBIND_AGAIN
        }
        val result = when (action) {
            RelayRepairAction.REQUEST_STATIC_UNBIND_REBIND -> RelayRepairResult.CLEAN_STATIC_REQUESTED
            RelayRepairAction.REQUEST_INSTANCE_UNBIND_REBIND -> RelayRepairResult.CLEAN_INSTANCE_REQUESTED
            RelayRepairAction.REQUEST_REBIND_AGAIN -> RelayRepairResult.REBIND_REPEATED
            else -> error("unexpected clean action")
        }
        return RelayRepairDecision(
            action,
            result,
            state.copy(
                phase = RelayRepairPhase.WAITING_FOR_CLEAN_REBIND,
                expectedGeneration = input.connectGeneration,
                deadlineUptimeMs = deadlineAfter(now, reconnectWaitMs),
                lastCleanUptimeMs = now,
            ),
            reconnectWaitMs,
        )
    }

    private fun evaluateCleanWait(
        state: RelayRepairState,
        input: RelayRepairInput,
        now: Long,
    ): RelayRepairDecision {
        if (now < state.deadlineUptimeMs) {
            return RelayRepairDecision(
                RelayRepairAction.NONE,
                RelayRepairResult.WAITING,
                state,
                state.deadlineUptimeMs - now,
            )
        }
        val failures = incrementFailures(state.failureCount)
        val retryAt = deadlineAfter(now, backoffFor(failures))
        return RelayRepairDecision(
            RelayRepairAction.NONE,
            RelayRepairResult.FAILED,
            state.copy(
                phase = RelayRepairPhase.IDLE,
                expectedGeneration = input.connectGeneration,
                deadlineUptimeMs = 0L,
                failureCount = failures,
                nextAttemptUptimeMs = retryAt,
            ),
            retryAt - now,
        )
    }

    private fun failedWithoutClean(
        state: RelayRepairState,
        generation: Long,
        now: Long,
    ): RelayRepairDecision {
        val failures = incrementFailures(state.failureCount)
        val retryAt = deadlineAfter(now, backoffFor(failures))
        return RelayRepairDecision(
            RelayRepairAction.NONE,
            RelayRepairResult.CLEAN_RATE_LIMITED,
            state.copy(
                phase = RelayRepairPhase.IDLE,
                expectedGeneration = generation,
                deadlineUptimeMs = 0L,
                failureCount = failures,
                nextAttemptUptimeMs = retryAt,
            ),
            retryAt - now,
        )
    }

    private fun connected(state: RelayRepairState, generation: Long): RelayRepairDecision {
        val repaired = state.phase != RelayRepairPhase.IDLE || state.failureCount > 0
        return RelayRepairDecision(
            RelayRepairAction.NONE,
            if (repaired) RelayRepairResult.CONNECTED else RelayRepairResult.HEALTHY,
            state.copy(
                phase = RelayRepairPhase.IDLE,
                expectedGeneration = generation,
                deadlineUptimeMs = 0L,
                failureCount = 0,
                nextAttemptUptimeMs = 0L,
            ),
        )
    }

    private fun normalizeClock(state: RelayRepairState, now: Long): RelayRepairState {
        val clockRolledBack = state.lastObservedUptimeMs > now ||
            state.lastCleanUptimeMs != RelayRepairState.NO_UPTIME && state.lastCleanUptimeMs > now
        if (!clockRolledBack) return state
        return RelayRepairState(
            expectedGeneration = state.expectedGeneration,
            lastObservedUptimeMs = now,
        )
    }

    private fun stamp(decision: RelayRepairDecision, now: Long): RelayRepairDecision =
        decision.copy(state = decision.state.copy(lastObservedUptimeMs = now))

    private fun incrementFailures(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun backoffFor(failureCount: Int): Long =
        failureBackoffMs[(failureCount - 1).coerceIn(0, failureBackoffMs.lastIndex)]

    private fun deadlineAfter(now: Long, delayMs: Long): Long =
        if (Long.MAX_VALUE - now < delayMs) Long.MAX_VALUE else now + delayMs

    companion object {
        const val RECONNECT_WAIT_MS = 15_000L
        const val CLEAN_RATE_LIMIT_MS = 15L * 60L * 1_000L
        val FAILURE_BACKOFF_MS = longArrayOf(60_000L, 5L * 60L * 1_000L, 15L * 60L * 1_000L)
        const val STATIC_REQUEST_UNBIND_API = 34
    }
}
