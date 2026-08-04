package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmBridgeLivenessPolicyTest {
    private var now = 0L

    private fun policy() = SelfArmBridgeLivenessPolicy { now }

    @Test
    fun onlyADifferentBootEpochIsPresumedDead() {
        // No record is "unknown", not "dead": installs armed before the epoch existed may hold a
        // healthy bridge.
        assertFalse(SelfArmBridgeLivenessPolicy.presumedDead(null, 1_000_000L))
        assertFalse(SelfArmBridgeLivenessPolicy.presumedDead(1_000_000L, 1_000_000L))
        assertFalse(SelfArmBridgeLivenessPolicy.presumedDead(1_000_000L, 1_005_000L))
        assertTrue(SelfArmBridgeLivenessPolicy.presumedDead(1_000_000L, 1_005_001L))
        // Symmetric: a wall clock stepped backwards is still a different epoch.
        assertTrue(SelfArmBridgeLivenessPolicy.presumedDead(1_005_001L, 1_000_000L))
    }

    @Test
    fun attemptsFollowTheBackoffLadder() {
        val policy = policy()
        val ladder = longArrayOf(2 * 60_000L, 8 * 60_000L, 30 * 60_000L, 2 * 3_600_000L, 2 * 3_600_000L)

        // The first attempt costs no wait at all.
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
        for (wait in ladder) {
            assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Backoff(wait), policy.claimAttempt())
            now += wait - 1
            assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Backoff(1L), policy.claimAttempt())
            now += 1
            assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
        }
    }

    @Test
    fun capIsSixAttemptsPerEpochNoMatterHowLongTheWait() {
        val policy = policy()
        repeat(SelfArmBridgeLivenessPolicy.MAX_ATTEMPTS_PER_EPOCH) {
            assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
            now += 3 * 3_600_000L
        }
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.CapExhausted, policy.claimAttempt())
        now += 24 * 3_600_000L
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.CapExhausted, policy.claimAttempt())
    }

    @Test
    fun everyResetConditionRestoresTheFullBudget() {
        // The wiring maps four conditions onto reset(): a successful arm, a Wi-Fi network becoming
        // available, adb_wifi_enabled flipping to 1, and the owner opening the setup screen. The
        // remaining two — a new boot epoch and a package replacement — restart the process, which
        // rebuilds the policy instance; modeled here as a fresh object.
        val policy = policy()
        repeat(SelfArmBridgeLivenessPolicy.MAX_ATTEMPTS_PER_EPOCH) {
            assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
            now += 3 * 3_600_000L
        }
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.CapExhausted, policy.claimAttempt())

        policy.reset()
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
        // The ladder restarts from the top as well.
        assertEquals(
            SelfArmBridgeLivenessPolicy.Verdict.Backoff(2 * 60_000L),
            policy.claimAttempt(),
        )

        val freshProcess = policy()
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, freshProcess.claimAttempt())
    }

    @Test
    fun rejectedClaimIsRefundedWithoutBurningTheLadder() {
        val policy = policy()
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
        now += 2 * 60_000L
        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())

        // The self-arm single-flight was busy; nothing ran, so the claim is handed back.
        policy.onAttemptRejected()

        assertEquals(SelfArmBridgeLivenessPolicy.Verdict.Attempt, policy.claimAttempt())
        assertEquals(
            SelfArmBridgeLivenessPolicy.Verdict.Backoff(8 * 60_000L),
            policy.claimAttempt(),
        )
    }
}
