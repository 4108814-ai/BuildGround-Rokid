package com.anezium.rokidbus.phone.mediasync

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
 * Persistent-group hygiene, scoped to the media-sync SSID prefix.
 *
 * The prefix is deliberately different from the camera link's `DIRECT-RN-`: the Lens janitor
 * deletes every profile matching its own prefix, and photo sync must never be collateral damage
 * (nor cause any).
 */
object MediaSyncPersistentGroupPolicy {
    const val NETWORK_NAME_PREFIX = "DIRECT-NS-"
    const val MAX_RETAINED_OWNED_GROUPS = 2

    fun isOwnedGroup(networkName: String?): Boolean =
        networkName != null && networkName.startsWith(NETWORK_NAME_PREFIX)

    /**
     * Profiles to delete, oldest first, keeping [MAX_RETAINED_OWNED_GROUPS] of our own plus
     * anything explicitly retained (the group we are about to join, and the last one that worked).
     */
    fun networkIdsToDelete(
        groups: List<MediaSyncPersistentGroup>,
        retainedNetworkNames: List<String> = emptyList(),
    ): List<Int> {
        val retained = retainedNetworkNames.filter(String::isNotBlank).toSet()
        val owned = groups.filter { isOwnedGroup(it.networkName) && it.networkName !in retained }
        if (owned.size <= MAX_RETAINED_OWNED_GROUPS) return emptyList()
        return owned.dropLast(MAX_RETAINED_OWNED_GROUPS).map(MediaSyncPersistentGroup::networkId)
    }
}

data class MediaSyncPersistentGroup(
    val networkName: String,
    val networkId: Int,
)
