package com.anezium.rokidbus.phone.mediasync

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure join/retry policies for the media-sync Wi-Fi Direct link.
 *
 * The numbers are lifted verbatim from the battle-tested camera link
 * (`plugins/lens/.../PhoneLensLinkPolicies.kt`) rather than retuned: the same phone, the same
 * supplicant and the same glasses group owner are involved. They live here as MediaSync-named
 * copies so a future camera-link change can never destabilise photo sync, and vice versa.
 */
class MediaSyncJoinRetryPolicy(
    private val initialDelayMs: Long = INITIAL_JOIN_RETRY_MS,
    private val delayStepMs: Long = JOIN_RETRY_STEP_MS,
    private val maxDelayMs: Long = MAX_JOIN_RETRY_MS,
    private val maxAttempts: Int = MAX_JOIN_ATTEMPTS,
) {
    private var attempts = 0

    /** Returns the 1-based attempt number, or null once the budget is exhausted. */
    fun startAttempt(): Int? {
        if (attempts >= maxAttempts) return null
        attempts += 1
        return attempts
    }

    fun retryDelayAfter(attempt: Int): Long? {
        if (attempt >= maxAttempts) return null
        return (initialDelayMs + (attempt - 1) * delayStepMs).coerceAtMost(maxDelayMs)
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        const val INITIAL_JOIN_RETRY_MS = 300L
        const val JOIN_RETRY_STEP_MS = 1_000L
        const val MAX_JOIN_RETRY_MS = 3_000L
        const val MAX_JOIN_ATTEMPTS = 6
    }
}

enum class MediaSyncJoinRecoveryAction {
    NONE,
    REMOVE_GROUP,
    RESET_CHANNEL,
}

/**
 * Escalation ladder after consecutive join failures: first just retry, then tear down whatever
 * group the framework is holding, then rebuild the whole P2P channel.
 */
class MediaSyncJoinRecoveryPolicy(
    private val removeGroupAfterFailures: Int = 2,
    private val resetChannelAfterFailures: Int = 4,
) {
    fun actionAfter(consecutiveFailures: Int): MediaSyncJoinRecoveryAction = when {
        consecutiveFailures <= 0 -> MediaSyncJoinRecoveryAction.NONE
        consecutiveFailures == resetChannelAfterFailures -> MediaSyncJoinRecoveryAction.RESET_CHANNEL
        consecutiveFailures == removeGroupAfterFailures -> MediaSyncJoinRecoveryAction.REMOVE_GROUP
        else -> MediaSyncJoinRecoveryAction.NONE
    }
}

/**
 * The glasses group is an autonomous group owner, which is invisible to `requestPeers`. One
 * discovery scan per join cycle exists purely to warm the supplicant's scan cache before
 * `connect()` — see the camera-link precedent.
 */
data class MediaSyncDiscoveryPrimingDecision(
    val shouldPrime: Boolean,
    val discoveryWaitMs: Long,
    val stopCallbackFallbackMs: Long,
)

class MediaSyncDiscoveryPrimingPolicy(
    private val discoveryWaitMs: Long = DISCOVERY_PRIME_WAIT_MS,
    private val stopCallbackFallbackMs: Long = STOP_DISCOVERY_FALLBACK_MS,
) {
    fun decision(alreadyPrimedForJoinCycle: Boolean): MediaSyncDiscoveryPrimingDecision =
        MediaSyncDiscoveryPrimingDecision(
            shouldPrime = !alreadyPrimedForJoinCycle,
            discoveryWaitMs = discoveryWaitMs,
            stopCallbackFallbackMs = stopCallbackFallbackMs,
        )

    companion object {
        const val DISCOVERY_PRIME_WAIT_MS = 2_000L
        const val STOP_DISCOVERY_FALLBACK_MS = 400L
    }
}

/**
 * One-shot claim for work that two independent callbacks may both reach.
 *
 * The priming cycle is the case that needs it: `stopPeerDiscovery`'s callback normally fires the
 * join, but the framework sometimes swallows it, so a fallback timer has to exist. Without a claim
 * both would connect — and a second `connect()` landing inside a healthy join is exactly what
 * turns into a BUSY error, an inflated failure count, and eventually a `removeGroup` that tears
 * down the join that was working.
 */
class MediaSyncSingleDispatch {
    private val dispatched = AtomicBoolean(false)

    /** True for the first caller only. */
    fun claim(): Boolean = dispatched.compareAndSet(false, true)

    fun isClaimed(): Boolean = dispatched.get()
}
