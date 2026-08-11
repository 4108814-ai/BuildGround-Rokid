package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupPairingResult

internal data class SelfArmPhonePairingCorrelation(
    val sessionId: String,
    val offerId: String,
)

/**
 * Tracks only correlation and monotonic lifetime metadata. The pairing code and endpoints are
 * deliberately never retained here after the offer has been sent.
 */
internal class SelfArmPhonePairingOfferTracker {
    private data class Outstanding(
        val sessionId: String,
        val offerId: String,
        val startedAtMillis: Long,
        val expiresAtMillis: Long,
    )

    private var outstanding: Outstanding? = null

    fun begin(
        sessionId: String,
        offerId: String,
        startedAtMillis: Long,
        ttlMillis: Long,
    ): Boolean {
        if (outstanding != null || sessionId.isBlank() || offerId.isBlank() || ttlMillis <= 0L) {
            return false
        }
        val expiresAtMillis = runCatching { Math.addExact(startedAtMillis, ttlMillis) }
            .getOrNull()
            ?: return false
        outstanding = Outstanding(sessionId, offerId, startedAtMillis, expiresAtMillis)
        return true
    }

    fun resolve(result: SetupPairingResult): Boolean {
        val current = outstanding ?: return false
        if (current.sessionId != result.sessionId || current.offerId != result.offerId) return false
        outstanding = null
        return true
    }

    fun expire(nowMillis: Long): Boolean {
        val current = outstanding ?: return false
        if (nowMillis < current.expiresAtMillis) return false
        outstanding = null
        return true
    }

    fun suspendsExpiry(nowMillis: Long, maxGraceMillis: Long): Boolean {
        val current = outstanding ?: return false
        return SelfArmPairingGracePolicy.suspendsExpiry(
            workerAlive = true,
            nowMillis = nowMillis,
            pairingStartedAtMillis = current.startedAtMillis,
            maxGraceMillis = maxGraceMillis,
        )
    }

    fun hasOutstanding(): Boolean = outstanding != null

    fun correlation(): SelfArmPhonePairingCorrelation? = outstanding?.let {
        SelfArmPhonePairingCorrelation(it.sessionId, it.offerId)
    }

    fun clear() {
        outstanding = null
    }
}
