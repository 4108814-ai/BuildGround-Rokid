package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHoldLifecycleTest {
    @Test
    fun `engaged assistant notice acquires a ceiling-bounded hold`() {
        val lifecycle = lifecycle()
        val lease = FakeLease()

        val transitions = lifecycle.begin(
            ownerId = ASSISTANT_NOTICE,
            seq = 1L,
            requested = NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = ASSISTANT_NOTICE,
                engaged = true,
            ),
            nowMs = 1_000L,
            leaseFactory = { lease },
        )

        assertEquals(1, transitions.size)
        assertTrue(transitions.single() is DisplayHoldTransition.Acquire)
        assertEquals(listOf(DisplayWakePolicy.DISPLAY_HOLD_CEILING_MS), lease.acquisitions)
        assertEquals(DisplayHoldPhase.HELD, lifecycle.snapshot()?.phase)
    }

    @Test
    fun `engaged updates renew across a long think without the wake rate limit`() {
        val lifecycle = lifecycle()
        val lease = FakeLease()
        lifecycle.begin(ASSISTANT_NOTICE, 1L, true, 0L) { lease }

        val first = lifecycle.renew(ASSISTANT_NOTICE, 2L, true, 6_000L) { lease }
        val second = lifecycle.renew(ASSISTANT_NOTICE, 3L, true, 12_000L) { lease }

        assertTrue(first is DisplayHoldTransition.Renew)
        assertTrue(second is DisplayHoldTransition.Renew)
        assertEquals(
            listOf(90_000L, 84_000L, 78_000L),
            lease.acquisitions,
        )
        assertEquals(12_000L, lifecycle.snapshot()?.lastRenewedAtMs)
    }

    @Test
    fun `passive error keeps an existing episode until it expires`() {
        val lifecycle = lifecycle()
        val lease = FakeLease()
        lifecycle.begin(ASSISTANT_NOTICE, 1L, true, 0L) { lease }

        val passiveError = lifecycle.renew(
            ownerId = ASSISTANT_NOTICE,
            seq = 2L,
            eligibleToStart = false,
            nowMs = 10_000L,
            leaseFactory = { lease },
        )
        val expired = lifecycle.end(DisplayHoldReleaseReason.NOTICE_TIMEOUT, 12_500L)

        assertTrue(passiveError is DisplayHoldTransition.Renew)
        assertEquals(DisplayHoldReleaseReason.NOTICE_TIMEOUT, expired?.reason)
        assertEquals(1, lease.releases)
        assertNull(lifecycle.snapshot())
    }

    @Test
    fun `every terminal exit releases the held lock`() {
        val reasons = listOf(
            DisplayHoldReleaseReason.NOTICE_USER,
            DisplayHoldReleaseReason.NOTICE_TIMEOUT,
            DisplayHoldReleaseReason.NOTICE_OWNER,
            DisplayHoldReleaseReason.NOTICE_REPLACED,
            DisplayHoldReleaseReason.NOTICE_DISCONNECT,
            DisplayHoldReleaseReason.LINK_LOSS,
            DisplayHoldReleaseReason.SESSION_CLOSED,
            DisplayHoldReleaseReason.SURFACE_HIDDEN,
        )

        reasons.forEach { reason ->
            val lifecycle = lifecycle()
            val lease = FakeLease()
            lifecycle.begin(ASSISTANT_NOTICE, 1L, true, 0L) { lease }

            val release = lifecycle.end(reason, 1_000L)

            assertEquals("reason=$reason", reason, release?.reason)
            assertTrue("reason=$reason", release?.lockWasHeld == true)
            assertEquals("reason=$reason", 1, lease.releases)
            assertNull("reason=$reason", lifecycle.snapshot())
        }
    }

    @Test
    fun `service destruction releases and resume keeps the original ceiling`() {
        val lifecycle = lifecycle()
        val lease = FakeLease()
        lifecycle.begin(ASSISTANT_NOTICE, 1L, true, 1_000L) { lease }

        val suspended = lifecycle.suspend(DisplayHoldReleaseReason.SERVICE_DESTROYED, 10_000L)
        val resumed = lifecycle.renew(
            ownerId = ASSISTANT_NOTICE,
            seq = 2L,
            eligibleToStart = false,
            nowMs = 12_000L,
            leaseFactory = { lease },
        )

        assertEquals(DisplayHoldReleaseReason.SERVICE_DESTROYED, suspended?.reason)
        assertEquals(1, lease.releases)
        assertTrue(resumed is DisplayHoldTransition.Acquire && resumed.resumed)
        assertEquals(79_000L, (resumed as DisplayHoldTransition.Acquire).leaseMs)
        assertEquals(1_000L, lifecycle.snapshot()?.startedAtMs)
    }

    @Test
    fun `ceiling drops the lock and blocks reacquire in the same episode`() {
        val lifecycle = lifecycle()
        val lease = FakeLease()
        lifecycle.begin(ASSISTANT_NOTICE, 1L, true, 1_000L) { lease }

        assertNull(lifecycle.enforceCeiling(ASSISTANT_NOTICE, 1_000L, 90_999L))
        val ceiling = lifecycle.enforceCeiling(ASSISTANT_NOTICE, 1_000L, 91_000L)
        val afterCeiling = lifecycle.renew(ASSISTANT_NOTICE, 2L, true, 92_000L) { lease }

        assertTrue(ceiling is DisplayHoldTransition.Ceiling)
        assertEquals(1, lease.releases)
        assertEquals(DisplayHoldPhase.CEILING, lifecycle.snapshot()?.phase)
        assertNull(afterCeiling)
        assertEquals(1, lease.acquisitions.size)
    }

    @Test
    fun `ordinary and passive assistant notices never create a hold`() {
        val lifecycle = lifecycle()
        var leaseRequested = false
        val ordinaryRequested = NoticeDisplayHoldPolicy.noticeHoldsDisplay(
            surfaceId = "relay:notice",
            engaged = true,
        )
        val passiveAssistantRequested = NoticeDisplayHoldPolicy.noticeHoldsDisplay(
            surfaceId = ASSISTANT_NOTICE,
            engaged = false,
        )

        val ordinary = lifecycle.begin("relay:notice", 1L, ordinaryRequested, 0L) {
            leaseRequested = true
            FakeLease()
        }
        val passive = lifecycle.begin(ASSISTANT_NOTICE, 2L, passiveAssistantRequested, 1_000L) {
            leaseRequested = true
            FakeLease()
        }

        assertFalse(ordinaryRequested)
        assertFalse(passiveAssistantRequested)
        assertTrue(ordinary.isEmpty())
        assertTrue(passive.isEmpty())
        assertFalse(leaseRequested)
        assertNull(lifecycle.snapshot())
    }

    private fun lifecycle() = DisplayHoldLifecycle(DisplayWakePolicy.DISPLAY_HOLD_CEILING_MS)

    private class FakeLease : DisplayHoldLease {
        val acquisitions = mutableListOf<Long>()
        var releases = 0

        override fun acquire(timeoutMs: Long) {
            acquisitions += timeoutMs
        }

        override fun release() {
            releases += 1
        }
    }

    private companion object {
        const val ASSISTANT_NOTICE = "assistant:notice"
    }
}
