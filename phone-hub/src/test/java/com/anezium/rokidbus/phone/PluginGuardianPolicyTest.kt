package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginGuardianPolicyTest {
    @Test
    fun `initial link down does not start a linger`() {
        val policy = GuardianBindLifetimePolicy()

        assertEquals(GuardianLinkDecision.None, policy.onLinkStateChanged(false, 1_000L))
        assertEquals(GuardianLinkDecision.None, policy.onReleaseTimer(31_000L))
    }

    @Test
    fun `link loss releases only after the awake-uptime linger`() {
        val policy = GuardianBindLifetimePolicy()

        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 1_000L))
        assertEquals(
            GuardianLinkDecision.ScheduleRelease(30_000L),
            policy.onLinkStateChanged(false, 2_000L),
        )
        assertEquals(
            GuardianLinkDecision.ScheduleRelease(10_000L),
            policy.onReleaseTimer(22_000L),
        )
        assertEquals(GuardianLinkDecision.Release, policy.onReleaseTimer(32_000L))
        assertEquals(GuardianLinkDecision.None, policy.onReleaseTimer(62_000L))
    }

    @Test
    fun `link flap cancels the pending release`() {
        val policy = GuardianBindLifetimePolicy()

        policy.onLinkStateChanged(true, 1_000L)
        policy.onLinkStateChanged(false, 2_000L)

        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 3_000L))
        assertEquals(GuardianLinkDecision.None, policy.onReleaseTimer(32_000L))
        assertEquals(true, policy.isLinkUp)
    }

    @Test
    fun `stable repeated states neither extend the linger nor release early`() {
        val policy = GuardianBindLifetimePolicy()

        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 1_000L))
        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 2_000L))
        assertEquals(
            GuardianLinkDecision.ScheduleRelease(30_000L),
            policy.onLinkStateChanged(false, 3_000L),
        )
        assertEquals(GuardianLinkDecision.None, policy.onLinkStateChanged(false, 20_000L))
        assertEquals(GuardianLinkDecision.Release, policy.onReleaseTimer(33_000L))
    }

    @Test
    fun `binding retry backoff is bounded at five minutes`() {
        assertEquals(1_000L, GuardianBindRetryPolicy.delayMillis(1))
        assertEquals(5_000L, GuardianBindRetryPolicy.delayMillis(2))
        assertEquals(30_000L, GuardianBindRetryPolicy.delayMillis(3))
        assertEquals(60_000L, GuardianBindRetryPolicy.delayMillis(4))
        assertEquals(300_000L, GuardianBindRetryPolicy.delayMillis(5))
        assertEquals(300_000L, GuardianBindRetryPolicy.delayMillis(50))
    }
}
