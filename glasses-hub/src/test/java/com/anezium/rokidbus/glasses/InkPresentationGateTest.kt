package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPresentationGateTest {
    @Test
    fun `committed show waits for a positive-bounds draw and releases once`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 7L)
        gate.retainForSurface(SURFACE_ID, seq = 7L)

        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 7L, widthPx = 0, heightPx = 640))
        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 7L, widthPx = 480, heightPx = 0))
        assertTrue(gate.releaseAfterDraw(SURFACE_ID, 7L, widthPx = 480, heightPx = 640))
        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 7L, widthPx = 480, heightPx = 640))
    }

    @Test
    fun `stale draw cannot release a newer show`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 8L)

        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 7L, widthPx = 480, heightPx = 640))
        assertFalse(gate.releaseAfterDraw("assistant:other", 8L, widthPx = 480, heightPx = 640))
        assertTrue(gate.releaseAfterDraw(SURFACE_ID, 8L, widthPx = 480, heightPx = 640))
    }

    @Test
    fun `newer projection advances a pending show and rejects its stale frame`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 9L)
        gate.retainForSurface(SURFACE_ID, seq = 10L)

        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 9L, widthPx = 480, heightPx = 640))
        assertTrue(gate.releaseAfterDraw(SURFACE_ID, 10L, widthPx = 480, heightPx = 640))
    }

    @Test
    fun `replacement or clear cancels the pending show`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 11L)
        gate.retainForSurface("relay:other", seq = 12L)

        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 11L, widthPx = 480, heightPx = 640))

        gate.arm(SURFACE_ID, seq = 13L)
        gate.cancel()
        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 13L, widthPx = 480, heightPx = 640))
    }

    @Test
    fun `metrics captured during a display power transition cannot release the show`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 14L)

        assertFalse(
            gate.releaseAfterDraw(
                SURFACE_ID,
                14L,
                widthPx = 426,
                heightPx = 592,
                displayTransitioning = true,
            ),
        )
        assertTrue(
            gate.releaseAfterDraw(
                SURFACE_ID,
                14L,
                widthPx = 444,
                heightPx = 592,
                displayTransitioning = false,
            ),
        )
    }

    @Test
    fun `matching timeout force releases once and rejects a late draw`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 15L)

        assertTrue(gate.isPending(SURFACE_ID, 15L))
        assertTrue(gate.forceRelease(SURFACE_ID, 15L))
        assertFalse(gate.isPending(SURFACE_ID, 15L))
        assertFalse(gate.forceRelease(SURFACE_ID, 15L))
        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 15L, widthPx = 441, heightPx = 420))
    }

    @Test
    fun `timeout releases a frame blocked by display transition`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 16L)

        assertFalse(
            gate.releaseAfterDraw(
                SURFACE_ID,
                16L,
                widthPx = 441,
                heightPx = 420,
                displayTransitioning = true,
            ),
        )
        assertTrue(gate.forceRelease(SURFACE_ID, 16L))
    }

    @Test
    fun `retained sequence rejects stale draw and stale timeout`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 17L)
        val generation = gate.pendingGeneration(SURFACE_ID, 17L)
        gate.retainForSurface(SURFACE_ID, seq = 18L)

        assertEquals(generation, gate.pendingGeneration(SURFACE_ID, 18L))
        assertFalse(gate.forceRelease(SURFACE_ID, 17L))
        assertFalse(gate.releaseAfterDraw(SURFACE_ID, 17L, widthPx = 441, heightPx = 420))
        assertTrue(gate.forceRelease(SURFACE_ID, 18L))
    }

    @Test
    fun `fresh same ID show gets a distinct presentation generation`() {
        val gate = InkPresentationGate()
        gate.arm(SURFACE_ID, seq = 19L)
        val first = gate.pendingGeneration(SURFACE_ID, 19L)

        gate.arm(SURFACE_ID, seq = 20L)

        assertFalse(first == gate.pendingGeneration(SURFACE_ID, 20L))
    }

    private companion object {
        const val SURFACE_ID = "assistant:assistant-ink"
    }
}
