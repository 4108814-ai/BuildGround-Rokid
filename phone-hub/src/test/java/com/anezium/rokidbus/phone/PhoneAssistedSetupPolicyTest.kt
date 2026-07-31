package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.SetupPairingFailureReason
import com.anezium.rokidbus.shared.SetupPairingOffer
import com.anezium.rokidbus.shared.SetupPairingOfferContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAssistedSetupPolicyTest {
    @Test
    fun `expired offer uses receiver clock even when glasses clock is far in the past`() {
        val decision = policy().evaluate(
            offer = offer(issuedAt = -9_000_000_000L, expiresAt = -8_999_910_000L),
            currentSessionId = SESSION,
            lastUserIntentAtMillis = 1L,
            arrivedAtMillis = 1_000L,
            nowMillis = 91_000L,
        )

        assertRejected(SetupPairingFailureReason.EXPIRED, decision)
    }

    @Test
    fun `expired offer uses receiver clock even when glasses clock is far in the future`() {
        val decision = policy().evaluate(
            offer = offer(issuedAt = 9_000_000_000L, expiresAt = 9_000_090_000L),
            currentSessionId = SESSION,
            lastUserIntentAtMillis = 1L,
            arrivedAtMillis = 1_000L,
            nowMillis = 91_000L,
        )

        assertRejected(SetupPairingFailureReason.EXPIRED, decision)
    }

    @Test
    fun `replayed offer id is rejected`() {
        val policy = policy()
        val offer = offer()
        val first = policy.evaluate(offer, SESSION, 1L, 10L, 11L)
        val second = policy.evaluate(offer, SESSION, 1L, 12L, 13L)

        assertTrue(first is PhoneAssistedSetupOfferPolicy.Decision.Accepted)
        assertRejected(SetupPairingFailureReason.REPLAYED, second)
    }

    @Test
    fun `mismatched session is rejected`() {
        val decision = policy().evaluate(
            offer(),
            currentSessionId = OTHER_SESSION,
            lastUserIntentAtMillis = 1L,
            arrivedAtMillis = 10L,
            nowMillis = 11L,
        )

        assertRejected(SetupPairingFailureReason.WRONG_SESSION, decision)
    }

    @Test
    fun `offer without recent owner request is rejected`() {
        val decision = policy().evaluate(
            offer(),
            currentSessionId = SESSION,
            lastUserIntentAtMillis = 0L,
            arrivedAtMillis = 10L,
            nowMillis = 11L,
        )

        assertRejected(SetupPairingFailureReason.NOT_REQUESTED, decision)
    }

    @Test
    fun `new phone advertises assisted setup without putting offer data in capabilities`() {
        val advertised = PhoneAssistedSetupCapabilityPolicy.advertised(0)

        assertEquals(BusCapabilityBits.PHONE_ASSISTED_SETUP, advertised)
    }

    private fun policy() = PhoneAssistedSetupOfferPolicy()

    private fun offer(
        issuedAt: Long = 100L,
        expiresAt: Long = issuedAt + SetupPairingOfferContract.MAX_TTL_MS,
    ): SetupPairingOffer = requireNotNull(
        SetupPairingOfferContract.createOffer(
            sessionId = SESSION,
            offerId = OFFER_ID,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            host = HOST,
            pairingPort = PAIR_PORT,
            connectPort = CONNECT_PORT,
            pairingCode = CODE,
        ),
    )

    private fun assertRejected(
        reason: String,
        decision: PhoneAssistedSetupOfferPolicy.Decision,
    ) {
        assertEquals(
            PhoneAssistedSetupOfferPolicy.Decision.Rejected(reason),
            decision,
        )
    }

    private companion object {
        const val SESSION = "0123456789abcdef"
        const val OTHER_SESSION = "fedcba9876543210"
        const val OFFER_ID = "0011223344556677"
        const val HOST = "192.168.4.2"
        const val PAIR_PORT = 37123
        const val CONNECT_PORT = 39876
        const val CODE = "123456"
    }
}
