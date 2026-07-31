package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.SetupPairingFailureReason
import com.anezium.rokidbus.shared.SetupPairingOffer
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import java.util.ArrayDeque

internal object PhoneAssistedSetupCapabilityPolicy {
    fun advertised(features: Int): Int =
        features or BusCapabilityBits.PHONE_ASSISTED_SETUP
}

/**
 * Pure admission policy for a pairing offer that has already arrived over the authenticated
 * glasses-hub transport. Only offer IDs are retained for replay protection; endpoints and pairing
 * codes never enter this object.
 */
internal class PhoneAssistedSetupOfferPolicy(
    private val replayCapacity: Int = DEFAULT_REPLAY_CAPACITY,
) {
    sealed interface Decision {
        data class Accepted(val ttlMillis: Long) : Decision
        data class Rejected(val reason: String) : Decision
    }

    private val replayOrder = ArrayDeque<String>()
    private val replayIds = HashSet<String>()

    init {
        require(replayCapacity > 0)
    }

    @Synchronized
    fun evaluate(
        offer: SetupPairingOffer,
        currentSessionId: String,
        lastUserIntentAtMillis: Long,
        arrivedAtMillis: Long,
        nowMillis: Long,
    ): Decision {
        if (offer.sessionId != currentSessionId) {
            return Decision.Rejected(SetupPairingFailureReason.WRONG_SESSION)
        }
        if (!hasRecentUserIntent(lastUserIntentAtMillis, nowMillis)) {
            return Decision.Rejected(SetupPairingFailureReason.NOT_REQUESTED)
        }
        if (offer.offerId in replayIds) {
            return Decision.Rejected(SetupPairingFailureReason.REPLAYED)
        }
        remember(offer.offerId)

        val ttlMillis = SetupPairingOfferContract.ttlMillis(offer)
            ?: return Decision.Rejected(SetupPairingFailureReason.EXPIRED)
        if (nowMillis < arrivedAtMillis || nowMillis - arrivedAtMillis >= ttlMillis) {
            return Decision.Rejected(SetupPairingFailureReason.EXPIRED)
        }
        return Decision.Accepted(ttlMillis)
    }

    private fun hasRecentUserIntent(lastUserIntentAtMillis: Long, nowMillis: Long): Boolean =
        lastUserIntentAtMillis > 0L &&
            nowMillis >= lastUserIntentAtMillis &&
            nowMillis - lastUserIntentAtMillis <= USER_INTENT_WINDOW_MS

    private fun remember(offerId: String) {
        replayIds += offerId
        replayOrder.addLast(offerId)
        while (replayOrder.size > replayCapacity) {
            replayIds -= replayOrder.removeFirst()
        }
    }

    companion object {
        const val USER_INTENT_WINDOW_MS = 10 * 60_000L
        const val DEFAULT_REPLAY_CAPACITY = 32
    }
}
