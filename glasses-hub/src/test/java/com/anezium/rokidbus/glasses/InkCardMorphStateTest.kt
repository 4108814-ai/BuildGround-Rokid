package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkCardMorphStateTest {
    @Test
    fun `reveal frame starts at band bounds and ends at full card`() {
        assertEquals(
            InkCardMorphFrame(clipHeightPx = 88, cardAlpha = 0f, bandAlpha = 0.8f),
            inkCardMorphFrame(
                bandHeightPx = 88,
                cardHeightPx = 420,
                clipProgress = 0f,
                alphaProgress = 0f,
                initialBandAlpha = 0.8f,
            ),
        )
        assertEquals(
            InkCardMorphFrame(clipHeightPx = 420, cardAlpha = 1f, bandAlpha = 0f),
            inkCardMorphFrame(
                bandHeightPx = 88,
                cardHeightPx = 420,
                clipProgress = 1f,
                alphaProgress = 1f,
                initialBandAlpha = 0.8f,
            ),
        )
    }

    @Test
    fun `band cannot tear down before first frame or animation completion`() {
        val state = InkCardMorphState()

        assertEquals(InkCardMorphPhase.WAITING_FOR_FIRST_FRAME, state.phase)
        assertFalse(state.bandMayTearDown)
        assertTrue(state.startAnimation())
        assertFalse(state.bandMayTearDown)
        assertTrue(state.completeAnimation())
        assertTrue(state.bandMayTearDown)
        assertFalse(state.commitInstant())
    }

    @Test
    fun `timeout commit wins once and ignores a late first frame`() {
        val state = InkCardMorphState()

        assertTrue(state.commitInstant())
        assertEquals(InkCardMorphPhase.COMMITTED, state.phase)
        assertTrue(state.bandMayTearDown)
        assertFalse(state.startAnimation())
        assertFalse(state.commitInstant())
    }

    @Test
    fun `animator failure can atomically replace a partial animation with instant commit`() {
        val state = InkCardMorphState()

        assertTrue(state.startAnimation())
        assertTrue(state.commitInstant())
        assertEquals(InkCardMorphPhase.COMMITTED, state.phase)
        assertFalse(state.completeAnimation())
    }

    @Test
    fun `replacement cancels a waiting handoff without granting teardown to its completion`() {
        val state = InkCardMorphState()

        state.cancel()

        assertEquals(InkCardMorphPhase.CANCELLED, state.phase)
        assertFalse(state.bandMayTearDown)
        assertFalse(state.startAnimation())
        assertFalse(state.commitInstant())
    }
}
