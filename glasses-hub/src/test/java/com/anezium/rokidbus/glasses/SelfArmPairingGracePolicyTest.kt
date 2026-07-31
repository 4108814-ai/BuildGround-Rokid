package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmPairingGracePolicyTest {
    @Test
    fun `a live pairing past the dialog hold still suspends expiry`() {
        // 90 s in: past PAIRING_DIALOG_HOLD_MS (60 s) and past the 110 s run deadline is where
        // the wearer used to be told setup had stopped while the bootstrap was still working.
        assertTrue(
            SelfArmPairingGracePolicy.suspendsExpiry(
                workerAlive = true,
                nowMillis = 90_000L,
                pairingStartedAtMillis = 0L + 1L,
                maxGraceMillis = GRACE_MS,
            ),
        )
    }

    @Test
    fun `a dead worker stops protecting the run`() {
        assertFalse(
            SelfArmPairingGracePolicy.suspendsExpiry(
                workerAlive = false,
                nowMillis = 90_000L,
                pairingStartedAtMillis = 1L,
                maxGraceMillis = GRACE_MS,
            ),
        )
    }

    @Test
    fun `a hung worker stops protecting the run once the grace is spent`() {
        assertFalse(
            SelfArmPairingGracePolicy.suspendsExpiry(
                workerAlive = true,
                nowMillis = GRACE_MS + 1L,
                pairingStartedAtMillis = 1L,
                maxGraceMillis = GRACE_MS,
            ),
        )
    }

    @Test
    fun `no pairing recorded never suspends expiry`() {
        assertFalse(
            SelfArmPairingGracePolicy.suspendsExpiry(
                workerAlive = true,
                nowMillis = 10_000L,
                pairingStartedAtMillis = 0L,
                maxGraceMillis = GRACE_MS,
            ),
        )
    }

    @Test
    fun `a clock that moved backwards never suspends expiry`() {
        assertFalse(
            SelfArmPairingGracePolicy.suspendsExpiry(
                workerAlive = true,
                nowMillis = 5_000L,
                pairingStartedAtMillis = 10_000L,
                maxGraceMillis = GRACE_MS,
            ),
        )
    }

    private companion object {
        const val GRACE_MS = 150_000L
    }
}
