package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeOverlayRendererTest {
    @Test
    fun `a new notice reverses an exit at every fade point`() {
        listOf(1f, 0.75f, 0.01f, 0f).forEach { fadeAlpha ->
            assertEquals(
                NoticeRenderMotion.REENTER,
                noticeRenderMotion(fadeAlpha, exitRunning = true),
            )
        }
    }

    @Test
    fun `a notice enters from detached alpha zero`() {
        assertEquals(
            NoticeRenderMotion.ENTER,
            noticeRenderMotion(fadeAlpha = 0f, exitRunning = false),
        )
    }

    @Test
    fun `an ordinary visible update does not restart entry`() {
        assertEquals(
            NoticeRenderMotion.UPDATE,
            noticeRenderMotion(fadeAlpha = 0.4f, exitRunning = false),
        )
        assertEquals(
            NoticeRenderMotion.UPDATE,
            noticeRenderMotion(fadeAlpha = 1f, exitRunning = false),
        )
    }

    @Test
    fun `only an assistant conversational notice holds the display`() {
        assertTrue(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:notice",
                wakeDisplay = false,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:notice",
                wakeDisplay = true,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "relay:notice",
                wakeDisplay = false,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.noticeHoldsDisplay(
                surfaceId = "assistant:activity",
                wakeDisplay = false,
            ),
        )
    }

    @Test
    fun `display hold stays continuous from assistant notice through surface handoff`() {
        assertTrue(
            NoticeDisplayHoldPolicy.displayHeld(
                noticeHolding = true,
                surfaceHolding = false,
            ),
        )
        assertTrue(
            NoticeDisplayHoldPolicy.displayHeld(
                noticeHolding = true,
                surfaceHolding = true,
            ),
        )
        assertTrue(
            NoticeDisplayHoldPolicy.displayHeld(
                noticeHolding = false,
                surfaceHolding = true,
            ),
        )
        assertFalse(
            NoticeDisplayHoldPolicy.displayHeld(
                noticeHolding = false,
                surfaceHolding = false,
            ),
        )
    }
}
