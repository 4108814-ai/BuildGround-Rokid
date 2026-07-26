package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeField
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfacePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeStateMachineTest {

    @Test
    fun `show sets the ttl deadline from now`() {
        val state = NoticeStateMachine()

        val decision = state.show("relay:notice", seq = 1, content = content(ttlMs = 8_000L), nowMs = 1_000L)

        val notice = (decision as NoticeStateDecision.Shown).notice
        assertEquals(9_000L, notice.expiresAtMs)
    }

    @Test
    fun `a stale sequence is dropped and leaves the slot alone`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 5, content = content(), nowMs = 0L)

        val decision = state.show("other:notice", seq = 3, content = content(), nowMs = 0L)

        assertTrue(decision is NoticeStateDecision.DroppedStale)
        assertEquals("relay:notice", state.activeNotice()?.surfaceId)
    }

    @Test
    fun `an update patches the visible notice and restarts the clock`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(ttlMs = 8_000L), nowMs = 0L)

        val decision = state.update(
            surfaceId = "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(footer = NoticeField("Listening…")),
            nowMs = 5_000L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertEquals("Listening…", notice.content.footer)
        assertEquals("Marie", notice.content.title)
        assertEquals(13_000L, notice.expiresAtMs)
    }

    @Test
    fun `an update from another plugin is ignored, not applied`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        val decision = state.update(
            surfaceId = "maps:notice",
            seq = 2,
            patch = NoticeSurfacePatch(title = NoticeField("Hijacked")),
            nowMs = 0L,
        )

        assertTrue(decision is NoticeStateDecision.Ignored)
        assertEquals("Marie", state.activeNotice()?.content?.title)
    }

    @Test
    fun `an update arriving after the notice is gone is ignored, not an error`() {
        val state = NoticeStateMachine()

        val decision = state.update(
            surfaceId = "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(footer = NoticeField("Sent")),
            nowMs = 0L,
        )

        assertTrue(decision is NoticeStateDecision.Ignored)
    }

    @Test
    fun `an update may not empty the notice of all its text`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        val decision = state.update(
            surfaceId = "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(title = NoticeField(null), body = NoticeField(null)),
            nowMs = 0L,
        )

        assertTrue(decision is NoticeStateDecision.Ignored)
        assertEquals("Marie", state.activeNotice()?.content?.title)
    }

    @Test
    fun `another plugin taking the slot closes the notice that had it`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        state.show("maps:notice", seq = 2, content = content(), nowMs = 0L)

        assertEquals("maps:notice", state.activeNotice()?.surfaceId)
    }

    @Test
    fun `back closes with the user reason and empties the slot`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        val decision = state.close(NoticeCloseReason.USER)

        assertEquals(NoticeCloseReason.USER, (decision as NoticeStateDecision.Closed).reason)
        assertEquals("relay:notice", decision.surfaceId)
        assertNull(state.activeNotice())
    }

    @Test
    fun `expiry only fires for the sequence that scheduled it`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(ttlMs = 8_000L), nowMs = 0L)
        state.update(
            surfaceId = "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(footer = NoticeField("Listening…")),
            nowMs = 4_000L,
        )

        // The timer armed by the show fires late; the update already moved on.
        val stale = state.expire(nowMs = 8_000L, expectedSeq = 1)

        assertTrue(stale is NoticeStateDecision.Ignored)
        assertEquals("relay:notice", state.activeNotice()?.surfaceId)
    }

    @Test
    fun `expiry closes with the timeout reason once the deadline passes`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(ttlMs = 8_000L), nowMs = 0L)

        assertTrue(state.expire(nowMs = 7_999L, expectedSeq = 1) is NoticeStateDecision.Ignored)
        val closed = state.expire(nowMs = 8_000L, expectedSeq = 1)

        assertEquals(NoticeCloseReason.TIMEOUT, (closed as NoticeStateDecision.Closed).reason)
        assertNull(state.activeNotice())
    }

    private fun content(ttlMs: Long = 8_000L) = NoticeSurfaceContent(
        title = "Marie",
        body = "On my way",
        footer = null,
        interactive = false,
        ttlMs = ttlMs,
    )
}
