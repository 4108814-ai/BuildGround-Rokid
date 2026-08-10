package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeCloseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHoldLifecycleTest {
    @Test
    fun `episode spans band card follow-up band and replacement card without release`() {
        val harness = Harness()

        val firstBand = harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)
        val bandUpdate = harness.apply(assistantRedraw(seq = 2L), nowMs = 5_000L)
        val firstCard = harness.apply(
            assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
            nowMs = 10_000L,
        )
        val morphClose = harness.apply(
            assistantEpisodeNoticeClosedSignal(
                surfaceId = ASSISTANT_NOTICE,
                reason = NoticeCloseReason.OWNER,
                preserveOwnerClose = true,
            ),
            nowMs = 10_001L,
        )
        val followUpBand = harness.apply(assistantEngagement(seq = 3L), nowMs = 40_000L)
        val replacementCard = harness.apply(
            assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
            nowMs = 45_000L,
        )

        assertTrue(firstBand.single() is DisplayHoldTransition.Acquire)
        assertEquals(DisplayHoldRenewReason.BAND_UPDATE, renew(bandUpdate).reason)
        assertTrue(firstCard.isEmpty())
        assertTrue(morphClose.isEmpty())
        assertEquals(DisplayHoldRenewReason.FOLLOW_UP, renew(followUpBand).reason)
        assertTrue(replacementCard.isEmpty())
        assertEquals(1, harness.leases.size)
        assertEquals(
            listOf(90_000L, 85_000L, 90_000L),
            harness.leases.single().acquisitions,
        )
        assertEquals(0, harness.leases.single().releases)
        assertEquals(0L, harness.lifecycle.snapshot()?.startedAtMs)
        assertEquals(130_000L, harness.lifecycle.snapshot()?.deadlineAtMs)
        assertEquals(AssistantDisplayEpisode.OWNER_ID, harness.lifecycle.snapshot()?.ownerId)

        val end = harness.apply(
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.SESSION_CLOSED),
            nowMs = 50_000L,
        )

        assertEquals(DisplayHoldReleaseReason.SESSION_CLOSED, release(end).reason)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.snapshot())
    }

    @Test
    fun `redraws and projection events cannot rearm or resurrect an episode`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)

        val update = harness.apply(assistantRedraw(seq = 2L), nowMs = 30_000L)
        val answer = harness.apply(
            assistantEpisodeNoticeRedrawSignal(
                surfaceId = ASSISTANT_NOTICE,
                seq = 3L,
                engaged = true,
                reason = DisplayHoldRenewReason.BAND_ANSWER,
            ),
            nowMs = 60_000L,
        )
        val firstFrame = harness.apply(AssistantEpisodeSignal.None, nowMs = 70_000L)
        val deadlineCommit = harness.apply(AssistantEpisodeSignal.None, nowMs = 70_500L)

        assertEquals(60_000L, renew(update).leaseMs)
        assertEquals(30_000L, renew(answer).leaseMs)
        assertTrue(firstFrame.isEmpty())
        assertTrue(deadlineCommit.isEmpty())
        assertEquals(90_000L, harness.lifecycle.snapshot()?.deadlineAtMs)

        val ceiling = harness.lifecycle.enforceCeiling(
            episodeId = harness.lifecycle.snapshot()!!.episodeId,
            deadlineAtMs = 90_000L,
            nowMs = 90_000L,
        )
        val redrawAfterCeiling = harness.apply(assistantRedraw(seq = 4L), nowMs = 91_000L)

        assertEquals(DisplayHoldReleaseReason.SAFETY_CEILING, ceiling?.reason)
        assertTrue(redrawAfterCeiling.isEmpty())
        assertEquals(1, harness.leases.size)
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.snapshot())
    }

    @Test
    fun `genuine follow-up re-arms the deadline without restarting the episode`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)
        val original = harness.lifecycle.snapshot()!!

        val followUp = harness.apply(assistantEngagement(seq = 2L), nowMs = 80_000L)
        val extended = harness.lifecycle.snapshot()!!
        val staleCeiling = harness.lifecycle.enforceCeiling(
            episodeId = original.episodeId,
            deadlineAtMs = original.deadlineAtMs,
            nowMs = 90_000L,
        )

        assertEquals(DisplayHoldRenewReason.FOLLOW_UP, renew(followUp).reason)
        assertEquals(original.episodeId, extended.episodeId)
        assertEquals(original.startedAtMs, extended.startedAtMs)
        assertEquals(170_000L, extended.deadlineAtMs)
        assertNull(staleCeiling)
        assertNull(
            harness.lifecycle.enforceCeiling(
                extended.episodeId,
                extended.deadlineAtMs,
                nowMs = 169_999L,
            ),
        )

        val ceiling = harness.lifecycle.enforceCeiling(
            extended.episodeId,
            extended.deadlineAtMs,
            nowMs = 170_000L,
        )

        assertEquals(DisplayHoldReleaseReason.SAFETY_CEILING, ceiling?.reason)
        assertEquals(listOf(90_000L, 90_000L), harness.leases.single().acquisitions)
        assertEquals(1, harness.leases.single().releases)
    }

    @Test
    fun `the card being shown gives the wearer a full window to read it`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)

        // A slow answer: the card only reaches the wearer 45 s into the episode.
        val shown = harness.apply(
            assistantEpisodeAnswerShownSignal(ownerPluginId = "assistant", seq = 2L),
            nowMs = 45_000L,
        )
        val extended = harness.lifecycle.snapshot()!!

        assertEquals(DisplayHoldRenewReason.ANSWER_SHOWN, renew(shown).reason)
        assertEquals(1L, extended.episodeId)
        assertEquals(135_000L, extended.deadlineAtMs)

        // A patch to the same card is a redraw and must not extend anything.
        harness.apply(
            assistantEpisodeNoticeRedrawSignal(
                surfaceId = "assistant:notice",
                seq = 3L,
                engaged = true,
                reason = DisplayHoldRenewReason.BAND_UPDATE,
            ),
            nowMs = 50_000L,
        )

        assertEquals(135_000L, harness.lifecycle.snapshot()!!.deadlineAtMs)

        // Another plugin's card never touches the assistant's episode.
        harness.apply(
            assistantEpisodeAnswerShownSignal(ownerPluginId = "relay", seq = 4L),
            nowMs = 60_000L,
        )

        assertEquals(135_000L, harness.lifecycle.snapshot()!!.deadlineAtMs)
        assertEquals(1, harness.leases.size)
        assertEquals(0, harness.leases.single().releases)
    }

    @Test
    fun `every terminal route releases the episode exactly once`() {
        val routedEnds = listOf(
            assistantEpisodeNoticeClosedSignal(
                ASSISTANT_NOTICE,
                NoticeCloseReason.USER,
                preserveOwnerClose = false,
            ) to DisplayHoldReleaseReason.WEARER_DISMISSED,
            assistantEpisodeNoticeClosedSignal(
                ASSISTANT_NOTICE,
                NoticeCloseReason.OWNER,
                preserveOwnerClose = false,
            ) to DisplayHoldReleaseReason.SESSION_CLOSED,
            assistantEpisodeSurfacePresentedSignal("relay") to
                DisplayHoldReleaseReason.NON_ASSISTANT_SURFACE,
            assistantEpisodeNoticeShownSignal(
                surfaceId = "relay:notice",
                ownerPluginId = "relay",
                seq = 9L,
                engaged = true,
            ) to DisplayHoldReleaseReason.ENGAGED_NOTICE_TAKEOVER,
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.LINK_LOSS) to
                DisplayHoldReleaseReason.LINK_LOSS,
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.SERVICE_DESTROYED) to
                DisplayHoldReleaseReason.SERVICE_DESTROYED,
            assistantEpisodeSurfaceEndedSignal(
                ASSISTANT_PLUGIN,
                DisplayHoldReleaseReason.RENDERER_ERROR,
            ) to DisplayHoldReleaseReason.RENDERER_ERROR,
        )

        routedEnds.forEach { (signal, expectedReason) ->
            val harness = Harness()
            harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)

            val first = harness.apply(signal, nowMs = 1_000L)
            val duplicate = harness.apply(signal, nowMs = 1_001L)

            assertEquals("reason=$expectedReason", expectedReason, release(first).reason)
            assertTrue("reason=$expectedReason", duplicate.isEmpty())
            assertEquals("reason=$expectedReason", 1, harness.leases.single().releases)
            assertNull("reason=$expectedReason", harness.lifecycle.snapshot())
        }
    }

    @Test
    fun `ceiling is one terminal release and leaves no tombstone`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 7L), nowMs = 1_000L)
        val snapshot = harness.lifecycle.snapshot()!!

        assertNull(
            harness.lifecycle.enforceCeiling(
                snapshot.episodeId,
                snapshot.deadlineAtMs,
                nowMs = 90_999L,
            ),
        )
        val ceiling = harness.lifecycle.enforceCeiling(
            snapshot.episodeId,
            snapshot.deadlineAtMs,
            nowMs = 91_000L,
        )
        val repeated = harness.lifecycle.enforceCeiling(
            snapshot.episodeId,
            snapshot.deadlineAtMs,
            nowMs = 91_001L,
        )
        val laterClose = harness.apply(
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.SESSION_CLOSED),
            nowMs = 92_000L,
        )

        assertEquals(DisplayHoldReleaseReason.SAFETY_CEILING, ceiling?.reason)
        assertNull(repeated)
        assertTrue(laterClose.isEmpty())
        assertEquals(1, harness.leases.single().releases)
        assertNull(harness.lifecycle.snapshot())
    }

    @Test
    fun `launcher Ink ordinary notices and passive assistant notices never hold`() {
        val harness = Harness()

        val launcherInk = harness.apply(
            assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
            nowMs = 0L,
        )
        val ordinary = harness.apply(
            assistantEpisodeNoticeShownSignal(
                surfaceId = "relay:notice",
                ownerPluginId = "relay",
                seq = 1L,
                engaged = false,
            ),
            nowMs = 1_000L,
        )
        val passiveAssistant = harness.apply(
            assistantEpisodeNoticeShownSignal(
                surfaceId = ASSISTANT_NOTICE,
                ownerPluginId = ASSISTANT_PLUGIN,
                seq = 2L,
                engaged = false,
            ),
            nowMs = 2_000L,
        )
        val unrelatedSurface = harness.apply(
            assistantEpisodeSurfacePresentedSignal("relay"),
            nowMs = 3_000L,
        )

        assertTrue(launcherInk.isEmpty())
        assertTrue(ordinary.isEmpty())
        assertTrue(passiveAssistant.isEmpty())
        assertTrue(unrelatedSurface.isEmpty())
        assertEquals(0, harness.factoryCalls)
        assertTrue(harness.leases.isEmpty())
        assertNull(harness.lifecycle.snapshot())
    }

    @Test
    fun `no matching band card preserves an active episode but creates none from idle`() {
        val active = Harness()
        active.apply(assistantEngagement(seq = 1L), nowMs = 0L)
        val before = active.lifecycle.snapshot()

        val cardProjection = active.apply(
            assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
            nowMs = 5_000L,
        )
        val noMatchingBandCommit = active.apply(AssistantEpisodeSignal.None, nowMs = 5_500L)

        assertTrue(cardProjection.isEmpty())
        assertTrue(noMatchingBandCommit.isEmpty())
        assertEquals(before, active.lifecycle.snapshot())
        assertEquals(0, active.leases.single().releases)

        val idle = Harness()
        idle.apply(
            assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
            nowMs = 0L,
        )
        idle.apply(AssistantEpisodeSignal.None, nowMs = 500L)
        assertTrue(idle.leases.isEmpty())
        assertNull(idle.lifecycle.snapshot())
    }

    @Test
    fun `takeovers release from both band and card phases without acquiring a successor`() {
        listOf(false, true).forEach { cardPresented ->
            listOf(
                assistantEpisodeSurfacePresentedSignal("relay"),
                assistantEpisodeNoticeShownSignal(
                    surfaceId = "relay:notice",
                    ownerPluginId = "relay",
                    seq = 9L,
                    engaged = true,
                ),
            ).forEach { takeover ->
                val harness = Harness()
                harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)
                if (cardPresented) {
                    harness.apply(
                        assistantEpisodeSurfacePresentedSignal(ASSISTANT_PLUGIN),
                        nowMs = 500L,
                    )
                }

                val ended = harness.apply(takeover, nowMs = 1_000L)

                assertTrue("cardPresented=$cardPresented", ended.single() is DisplayHoldTransition.Release)
                assertEquals("cardPresented=$cardPresented", 1, harness.factoryCalls)
                assertEquals("cardPresented=$cardPresented", 1, harness.leases.single().releases)
            }
        }
    }

    @Test
    fun `timeout and morph owner close preserve while a real owner close ends`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)

        val timeout = harness.apply(
            assistantEpisodeNoticeClosedSignal(
                ASSISTANT_NOTICE,
                NoticeCloseReason.TIMEOUT,
                preserveOwnerClose = false,
            ),
            nowMs = 5_000L,
        )
        val morphOwnerClose = harness.apply(
            assistantEpisodeNoticeClosedSignal(
                ASSISTANT_NOTICE,
                NoticeCloseReason.OWNER,
                preserveOwnerClose = true,
            ),
            nowMs = 6_000L,
        )

        assertTrue(timeout.isEmpty())
        assertTrue(morphOwnerClose.isEmpty())
        assertEquals(0, harness.leases.single().releases)

        val sessionClose = harness.apply(
            assistantEpisodeNoticeClosedSignal(
                ASSISTANT_NOTICE,
                NoticeCloseReason.OWNER,
                preserveOwnerClose = false,
            ),
            nowMs = 7_000L,
        )

        assertEquals(DisplayHoldReleaseReason.SESSION_CLOSED, release(sessionClose).reason)
        assertEquals(1, harness.leases.single().releases)
    }

    @Test
    fun `duplicate global exits cannot double release`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)

        val link = harness.apply(
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.LINK_LOSS),
            nowMs = 1_000L,
        )
        val repeatedLink = harness.apply(
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.LINK_LOSS),
            nowMs = 1_001L,
        )
        val serviceDestroy = harness.apply(
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.SERVICE_DESTROYED),
            nowMs = 1_002L,
        )

        assertEquals(DisplayHoldReleaseReason.LINK_LOSS, release(link).reason)
        assertTrue(repeatedLink.isEmpty())
        assertTrue(serviceDestroy.isEmpty())
        assertEquals(1, harness.leases.single().releases)
    }

    @Test
    fun `one lease instance survives every renewal`() {
        val harness = Harness()
        harness.apply(assistantEngagement(seq = 1L), nowMs = 0L)
        val lease = harness.leases.single()

        harness.apply(assistantRedraw(seq = 2L), nowMs = 10_000L)
        harness.apply(assistantEngagement(seq = 3L), nowMs = 20_000L)
        harness.apply(assistantRedraw(seq = 4L), nowMs = 30_000L)

        assertEquals(1, harness.factoryCalls)
        assertSame(lease, harness.leases.single())
        assertEquals(listOf(90_000L, 80_000L, 90_000L, 80_000L), lease.acquisitions)
        assertEquals(0, lease.releases)
    }

    @Test
    fun `hold logs keep the diagnostic shape and fixed owner`() {
        val acquire = DisplayHoldTransition.Acquire(
            ownerId = AssistantDisplayEpisode.OWNER_ID,
            seq = 1L,
            ageMs = 0L,
            leaseMs = 90_000L,
        )
        val renew = DisplayHoldTransition.Renew(
            ownerId = AssistantDisplayEpisode.OWNER_ID,
            seq = 2L,
            ageMs = 10_000L,
            leaseMs = 90_000L,
            reason = DisplayHoldRenewReason.FOLLOW_UP,
        )
        val release = DisplayHoldTransition.Release(
            ownerId = AssistantDisplayEpisode.OWNER_ID,
            seq = 2L,
            ageMs = 20_000L,
            reason = DisplayHoldReleaseReason.SESSION_CLOSED,
            lockWasHeld = true,
        )

        assertEquals(
            "hold seq=1 decision=acquire reason=engaged leaseMs=90000 " +
                "ageMs=0 owner=assistant:episode",
            formatDisplayHoldTransition(acquire),
        )
        assertEquals(
            "hold seq=2 decision=renew reason=follow_up leaseMs=90000 " +
                "ageMs=10000 owner=assistant:episode",
            formatDisplayHoldTransition(renew),
        )
        assertEquals(
            "hold seq=2 decision=release reason=session_closed held=true " +
                "ageMs=20000 owner=assistant:episode",
            formatDisplayHoldTransition(release),
        )
        assertFalse(formatDisplayHoldTransition(release).contains("transfer"))
    }

    @Test
    fun `failed lease creation never leaves an active episode`() {
        val lifecycle = AssistantEpisodeHoldLifecycle(90_000L)

        val transitions = lifecycle.apply(
            assistantEngagement(seq = 1L),
            nowMs = 0L,
            leaseFactory = { null },
        )

        assertEquals(DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE, refused(transitions).reason)
        assertNull(lifecycle.snapshot())
    }

    private fun assistantEngagement(seq: Long): AssistantEpisodeSignal =
        assistantEpisodeNoticeShownSignal(
            surfaceId = ASSISTANT_NOTICE,
            ownerPluginId = ASSISTANT_PLUGIN,
            seq = seq,
            engaged = true,
        )

    private fun assistantRedraw(seq: Long): AssistantEpisodeSignal =
        assistantEpisodeNoticeRedrawSignal(
            surfaceId = ASSISTANT_NOTICE,
            seq = seq,
            engaged = true,
            reason = DisplayHoldRenewReason.BAND_UPDATE,
        )

    private fun renew(transitions: List<DisplayHoldTransition>): DisplayHoldTransition.Renew =
        transitions.single() as DisplayHoldTransition.Renew

    private fun release(transitions: List<DisplayHoldTransition>): DisplayHoldTransition.Release =
        transitions.single() as DisplayHoldTransition.Release

    private fun refused(transitions: List<DisplayHoldTransition>): DisplayHoldTransition.Refused =
        transitions.single() as DisplayHoldTransition.Refused

    private class Harness {
        val lifecycle = AssistantEpisodeHoldLifecycle(90_000L)
        val leases = mutableListOf<FakeLease>()
        var factoryCalls = 0

        fun apply(signal: AssistantEpisodeSignal, nowMs: Long): List<DisplayHoldTransition> =
            lifecycle.apply(signal, nowMs) {
                factoryCalls += 1
                FakeLease().also(leases::add)
            }
    }

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
        const val ASSISTANT_PLUGIN = "assistant"
        const val ASSISTANT_NOTICE = "assistant:notice"
    }
}
