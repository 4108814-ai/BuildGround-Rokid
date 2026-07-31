package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeField
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfacePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `the selection starts on the first action`() {
        val state = NoticeStateMachine()

        val decision = state.show(
            "relay:notice",
            seq = 1,
            content = content(actions = threeActions()),
            nowMs = 0L,
        )

        assertEquals(0, (decision as NoticeStateDecision.Shown).notice.selectedActionIndex)
    }

    @Test
    fun `forward and backward wrap around the row`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)

        assertEquals(1, moved(state, 1))
        assertEquals(2, moved(state, 1))
        assertEquals(0, moved(state, 1))
        assertEquals(2, moved(state, -1))
    }

    @Test
    fun `a notice with no actions has nothing to select`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        assertTrue(state.moveSelection(1) is NoticeStateDecision.Ignored)
        assertNull(state.selectedAction())
    }

    @Test
    fun `choosing does not buy the band more time`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(ttlMs = 8_000L, actions = threeActions()),
            nowMs = 1_000L,
        )

        val moved = state.moveSelection(1) as NoticeStateDecision.Updated

        assertEquals(9_000L, moved.notice.expiresAtMs)
        assertEquals(NoticeCloseReason.TIMEOUT, closedBy(state, nowMs = 9_000L).reason)
    }

    @Test
    fun `an update keeps the wearer on the action they were looking at`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)
        state.moveSelection(1)

        val reordered = listOf(threeActions()[2], threeActions()[1], threeActions()[0])
        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(actions = NoticeField(reordered)),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertEquals(1, notice.selectedActionIndex)
        assertEquals("later", state.selectedAction()?.id)
    }

    @Test
    fun `an update that drops the selected action falls back to the first`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)
        state.moveSelection(1)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(
                actions = NoticeField(listOf(NoticeAction("reply", "phone", "Reply"))),
            ),
            nowMs = 0L,
        )

        assertEquals(0, (decision as NoticeStateDecision.Updated).notice.selectedActionIndex)
        assertEquals("reply", state.selectedAction()?.id)
    }

    @Test
    fun `a band answers once, and the row leaves it`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)
        state.moveSelection(1)

        val answered = state.answer(CONFIRM_KEY)

        val answer = (answered as NoticeStateDecision.Answered).answer
        assertEquals("later", (answer as NoticeAnswer.Action).action.id)
        assertTrue(answered.notice.answered)
        // The question is answered, so the choices stop being on offer: nothing
        // to draw, nothing to step through, nothing left to fire.
        assertTrue(answered.notice.liveActions.isEmpty())
        assertFalse(answered.notice.expectsInput)
        assertTrue(state.moveSelection(1) is NoticeStateDecision.Ignored)
        assertNull(state.selectedAction())
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `a band with no row answers once too, with the plain input`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(interactive = true), nowMs = 0L)

        val answered = state.answer(CONFIRM_KEY)

        val answer = (answered as NoticeStateDecision.Answered).answer
        assertEquals(CONFIRM_KEY, (answer as NoticeAnswer.Input).keyCode)
        assertTrue(answered.notice.answered)
        assertFalse(answered.notice.expectsInput)
        // The second of two fast taps. Nothing to send, nothing to claim.
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `a band that asked for nothing answers nothing`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
        assertFalse(state.activeNotice()!!.answered)
    }

    @Test
    fun `an answered band does not fall back to firing input`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(interactive = true, actions = threeActions()),
            nowMs = 0L,
        )
        state.answer(CONFIRM_KEY)

        // The row is gone, but the band is not an interactive banner again: one
        // band, one reply, of either kind.
        assertFalse(state.activeNotice()!!.expectsInput)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `answering neither shortens nor extends the band`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(ttlMs = 8_000L, actions = threeActions()),
            nowMs = 1_000L,
        )

        val answered = state.answer(CONFIRM_KEY) as NoticeStateDecision.Answered

        assertEquals(9_000L, answered.notice.expiresAtMs)
        assertTrue(state.expire(nowMs = 8_999L, expectedSeq = 1) is NoticeStateDecision.Ignored)
        assertEquals(NoticeCloseReason.TIMEOUT, closedBy(state, nowMs = 9_000L).reason)
    }

    @Test
    fun `an update carrying actions is a new question`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)
        state.answer(CONFIRM_KEY)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(
                actions = NoticeField(listOf(NoticeAction("send", "phone", "Send"))),
            ),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertFalse(notice.answered)
        assertEquals(listOf(NoticeAction("send", "phone", "Send")), notice.liveActions)
        val answer = (state.answer(CONFIRM_KEY) as NoticeStateDecision.Answered).answer
        assertEquals("send", (answer as NoticeAnswer.Action).action.id)
    }

    @Test
    fun `an update carrying the interactive flag is a new question too`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(interactive = true), nowMs = 0L)
        state.answer(CONFIRM_KEY)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(interactive = NoticeField(true)),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertFalse(notice.answered)
        assertTrue(notice.expectsInput)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Answered)
    }

    @Test
    fun `clearing the interactive flag resets the answer with nothing left to ask`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(interactive = true), nowMs = 0L)
        state.answer(CONFIRM_KEY)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(interactive = NoticeField(false)),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertFalse(notice.answered)
        assertFalse(notice.expectsInput)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `an update carrying an empty row resets the flag with nothing to answer`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)
        state.answer(CONFIRM_KEY)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(actions = NoticeField(emptyList())),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertFalse(notice.answered)
        assertTrue(notice.liveActions.isEmpty())
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `an update carrying neither field drives an answered band as a display`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(interactive = true, actions = threeActions()),
            nowMs = 0L,
        )
        state.answer(CONFIRM_KEY)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = NoticeSurfacePatch(body = NoticeField("Sending your reply")),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertTrue(notice.answered)
        assertTrue(notice.liveActions.isEmpty())
        assertEquals("Sending your reply", notice.content.body)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    /**
     * The band's half of the trip a clear makes. The phone relays the owner's
     * patch instead of re-serialising its state, so a cleared field arrives as
     * a present-and-empty field and lands here as an actual clear.
     */
    @Test
    fun `a cleared footer survives the trip to the band`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(footer = "tap to reply"),
            nowMs = 0L,
        )

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = relayed(NoticeSurfacePatch(footer = NoticeField(null))),
            nowMs = 0L,
        )

        assertNull((decision as NoticeStateDecision.Updated).notice.content.footer)
        assertEquals("Marie", decision.notice.content.title)
    }

    @Test
    fun `un-asking stops an unanswered band claiming anything`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(interactive = true), nowMs = 0L)
        assertTrue(state.activeNotice()!!.expectsInput)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = relayed(NoticeSurfacePatch(interactive = NoticeField(false))),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertFalse(notice.content.interactive)
        // Nothing to claim, so the tap reaches whatever is under the band.
        assertFalse(notice.expectsInput)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `a relayed empty row clears the band's actions`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(actions = threeActions()), nowMs = 0L)

        val decision = state.update(
            "relay:notice",
            seq = 2,
            patch = relayed(NoticeSurfacePatch(actions = NoticeField(emptyList()))),
            nowMs = 0L,
        )

        val notice = (decision as NoticeStateDecision.Updated).notice
        assertTrue(notice.liveActions.isEmpty())
        assertTrue(state.moveSelection(1) is NoticeStateDecision.Ignored)
        assertTrue(state.answer(CONFIRM_KEY) is NoticeStateDecision.Ignored)
    }

    @Test
    fun `the index helpers wrap and refuse to invent a selection`() {
        assertEquals(0, nextNoticeActionIndex(current = 2, delta = 1, count = 3))
        assertEquals(2, nextNoticeActionIndex(current = 0, delta = -1, count = 3))
        assertEquals(0, nextNoticeActionIndex(current = 0, delta = 1, count = 0))

        assertEquals(
            0,
            preservedNoticeActionIndex(threeActions(), previousIndex = 1, next = emptyList()),
        )
        assertEquals(
            0,
            preservedNoticeActionIndex(emptyList(), previousIndex = 0, next = threeActions()),
        )
    }

    @Test
    fun `page arithmetic accounts for the shorter image page`() {
        assertEquals(1, noticePageCount(lineCount = 8, firstPageLines = 8, followingPageLines = 8))
        assertEquals(3, noticePageCount(lineCount = 17, firstPageLines = 8, followingPageLines = 8))
        assertEquals(4, noticePageCount(lineCount = 20, firstPageLines = 3, followingPageLines = 8))
    }

    @Test
    fun `body text reaches layout unchanged`() {
        val body = "One paragraph that the real layout wraps"
        val content = NoticeSurfaceContent(
            title = "Marie",
            body = body,
            footer = null,
        )

        assertTrue(body === noticeBodyText(content))
    }

    @Test
    fun `structured lines become hard breaks before measured page arithmetic`() {
        val lines = List(16) { index -> "message $index" }
        val content = NoticeSurfaceContent(
            title = "Thread",
            body = null,
            footer = null,
            lines = lines,
        )

        val textForLayout = checkNotNull(noticeBodyText(content))
        assertEquals(lines.joinToString("\n"), textForLayout)
        val hardBrokenLineCount = textForLayout.count { it == '\n' } + 1
        assertEquals(16, hardBrokenLineCount)
        assertEquals(
            2,
            noticePageCount(
                lineCount = hardBrokenLineCount,
                firstPageLines = 8,
                followingPageLines = 8,
            ),
        )
    }

    @Test
    fun `page windows select the measured lines without overlap or loss`() {
        assertEquals(
            NoticePageWindow(0, 3),
            noticePageWindow(
                pageIndex = 0,
                lineCount = 20,
                firstPageLines = 3,
                followingPageLines = 8,
            ),
        )
        assertEquals(
            NoticePageWindow(3, 11),
            noticePageWindow(
                pageIndex = 1,
                lineCount = 20,
                firstPageLines = 3,
                followingPageLines = 8,
            ),
        )
        assertEquals(
            NoticePageWindow(19, 20),
            noticePageWindow(
                pageIndex = 3,
                lineCount = 20,
                firstPageLines = 3,
                followingPageLines = 8,
            ),
        )
    }

    @Test
    fun `first page turn kills both countdowns and gestures restart inactivity`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(ttlMs = 45_000L),
            nowMs = 0L,
        )
        state.setPageCount("relay:notice", seq = 1, count = 4)

        val firstTurn = state.movePage(delta = 1, nowMs = 10_000L)
            as NoticeStateDecision.Updated
        assertTrue(firstTurn.notice.engaged)
        assertEquals(1, firstTurn.notice.pageIndex)
        assertEquals(40_000L, firstTurn.notice.expiresAtMs)

        val secondTurn = state.movePage(delta = 1, nowMs = 39_000L)
            as NoticeStateDecision.Updated
        assertEquals(69_000L, secondTurn.notice.expiresAtMs)
        val thirdTurn = state.movePage(delta = 1, nowMs = 68_000L)
            as NoticeStateDecision.Updated
        assertEquals(98_000L, thirdTurn.notice.expiresAtMs)

        // The original TTL and ninety-second lifetime are both dead after the
        // first turn; only thirty seconds without a gesture can close it.
        assertTrue(state.expire(nowMs = 90_000L, expectedSeq = 1) is NoticeStateDecision.Ignored)
        assertTrue(state.expire(nowMs = 97_999L, expectedSeq = 1) is NoticeStateDecision.Ignored)
        assertEquals(
            NoticeCloseReason.TIMEOUT,
            (state.expire(nowMs = 98_000L, expectedSeq = 1) as NoticeStateDecision.Closed).reason,
        )
    }

    @Test
    fun `an engaged boundary gesture restarts inactivity without wrapping pages`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)
        state.setPageCount("relay:notice", seq = 1, count = 2)
        state.movePage(delta = 1, nowMs = 1_000L)

        val held = state.movePage(delta = 1, nowMs = 20_000L)
            as NoticeStateDecision.Updated

        assertEquals(1, held.notice.pageIndex)
        assertEquals(50_000L, held.notice.expiresAtMs)
    }

    @Test
    fun `an answerable notice stays unpaged after its row leaves`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(actions = threeActions()),
            nowMs = 0L,
        )

        assertTrue(state.setPageCount("relay:notice", seq = 1, count = 4) is NoticeStateDecision.Ignored)
        assertEquals(1, state.activeNotice()!!.pageCount)
        state.answer(CONFIRM_KEY)
        assertTrue(state.setPageCount("relay:notice", seq = 1, count = 4) is NoticeStateDecision.Ignored)
        assertFalse(state.activeNotice()!!.isPaged)
    }

    @Test
    fun `a notice with one answer still pages, since nothing steps along a row of one`() {
        val state = NoticeStateMachine()
        state.show(
            "relay:notice",
            seq = 1,
            content = content(actions = listOf(NoticeAction("reply", "reply", "Reply"))),
            nowMs = 0L,
        )

        assertTrue(state.setPageCount("relay:notice", seq = 1, count = 4) is NoticeStateDecision.Updated)
        val notice = state.activeNotice()!!
        assertEquals(4, notice.pageCount)
        assertTrue(notice.isPaged)
        assertTrue(notice.claimsDirection)
        // Still answerable: paging did not cost the band its tap.
        assertTrue(notice.expectsInput)
    }

    @Test
    fun `a plain single page notice claims no input or direction`() {
        val state = NoticeStateMachine()
        state.show("relay:notice", seq = 1, content = content(), nowMs = 0L)

        assertFalse(state.activeNotice()!!.expectsInput)
        assertFalse(state.activeNotice()!!.claimsDirection)
    }

    /** `KeyEvent.KEYCODE_ENTER`, spelled out so the test needs no framework. */
    private val CONFIRM_KEY = 66

    /**
     * The patch the phone's relay produces for a clear, as the glasses'
     * validator hands it back. Built directly rather than round-tripped through
     * `toUpdatePayload`, because this module's unit tests have no real
     * `org.json`; that the serialisation actually yields this patch is pinned in
     * `NoticeSurfaceContractTest`.
     */
    private fun relayed(patch: NoticeSurfacePatch): NoticeSurfacePatch = patch

    private fun moved(state: NoticeStateMachine, delta: Int): Int =
        (state.moveSelection(delta) as NoticeStateDecision.Updated).notice.selectedActionIndex

    private fun closedBy(state: NoticeStateMachine, nowMs: Long): NoticeStateDecision.Closed =
        state.expire(nowMs = nowMs, expectedSeq = 1) as NoticeStateDecision.Closed

    private fun threeActions() = listOf(
        NoticeAction("reply", "phone", "Reply"),
        NoticeAction("later", "timer", "Later"),
        NoticeAction("ignore", "stop", "Ignore"),
    )

    private fun content(
        ttlMs: Long = 8_000L,
        interactive: Boolean = false,
        footer: String? = null,
        actions: List<NoticeAction> = emptyList(),
    ) = NoticeSurfaceContent(
        title = "Marie",
        body = "On my way",
        footer = footer,
        interactive = interactive,
        actions = actions,
        ttlMs = ttlMs,
    )
}
