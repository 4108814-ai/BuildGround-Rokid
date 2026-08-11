package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupPairingOfferContract
import com.anezium.rokidbus.shared.SetupPairingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmPhonePairingOfferTrackerTest {
    @Test
    fun `result from old session is ignored and cannot clear current offer`() {
        val tracker = SelfArmPhonePairingOfferTracker()
        assertTrue(tracker.begin(SESSION, OFFER_ID, 1L, SetupPairingOfferContract.MAX_TTL_MS))

        assertFalse(tracker.resolve(result(OLD_SESSION, OFFER_ID)))
        assertTrue(tracker.hasOutstanding())
        assertTrue(tracker.resolve(result(SESSION, OFFER_ID)))
        assertFalse(tracker.hasOutstanding())
    }

    @Test
    fun `second offer waits until first resolves or expires`() {
        val tracker = SelfArmPhonePairingOfferTracker()
        assertTrue(tracker.begin(SESSION, OFFER_ID, 1L, 10L))
        assertFalse(tracker.begin(SESSION, NEXT_OFFER_ID, 2L, 10L))

        assertTrue(tracker.expire(11L))
        assertTrue(tracker.begin(SESSION, NEXT_OFFER_ID, 12L, 10L))
    }

    @Test
    fun `late port correlation exists only while the phone offer is outstanding`() {
        val tracker = SelfArmPhonePairingOfferTracker()
        assertTrue(tracker.begin(SESSION, OFFER_ID, 1L, 10L))

        assertEquals(SelfArmPhonePairingCorrelation(SESSION, OFFER_ID), tracker.correlation())

        assertTrue(tracker.resolve(result(SESSION, OFFER_ID)))
        assertNull(tracker.correlation())
    }

    @Test
    fun `outstanding phone offer suspends expiry only within bounded grace`() {
        val tracker = SelfArmPhonePairingOfferTracker()
        assertTrue(tracker.begin(SESSION, OFFER_ID, 1L, 90_000L))

        assertTrue(tracker.suspendsExpiry(60_001L, 150_000L))
        assertFalse(tracker.suspendsExpiry(150_001L, 150_000L))
    }

    private fun result(sessionId: String, offerId: String) = SetupPairingResult(
        version = SetupPairingOfferContract.VERSION,
        sessionId = sessionId,
        offerId = offerId,
        ok = true,
        reason = "",
    )

    private companion object {
        const val SESSION = "0123456789abcdef"
        const val OLD_SESSION = "fedcba9876543210"
        const val OFFER_ID = "0011223344556677"
        const val NEXT_OFFER_ID = "8899aabbccddeeff"
    }
}
