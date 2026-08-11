package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePointerMoveCoalescerTest {
    @Test
    fun `link policy resets full loss and falls back only from an active native pointer`() {
        assertEquals(
            RemotePointerLinkAction.RESET,
            RemotePointerLinkPolicy.decide(
                connected = false,
                nativePointerAvailable = false,
                nativePointerActive = true,
            ),
        )
        assertEquals(
            RemotePointerLinkAction.SWITCH_TO_HUB,
            RemotePointerLinkPolicy.decide(
                connected = true,
                nativePointerAvailable = false,
                nativePointerActive = true,
            ),
        )
        assertEquals(
            RemotePointerLinkAction.KEEP,
            RemotePointerLinkPolicy.decide(
                connected = true,
                nativePointerAvailable = true,
                nativePointerActive = true,
            ),
        )
        assertEquals(
            RemotePointerLinkAction.KEEP,
            RemotePointerLinkPolicy.decide(
                connected = true,
                nativePointerAvailable = false,
                nativePointerActive = false,
            ),
        )
    }

    @Test
    fun `delta policy rejects invalid and zero input and bounds a single update`() {
        assertNull(RemotePointerDeltaPolicy.sanitize(Double.NaN, 0.0))
        assertNull(RemotePointerDeltaPolicy.sanitize(0.0, Double.POSITIVE_INFINITY))
        assertNull(RemotePointerDeltaPolicy.sanitize(0.0, -0.0))
        assertEquals(RemotePointerDelta(1.0, -1.0), RemotePointerDeltaPolicy.sanitize(4.0, -3.0))
    }

    @Test
    fun `position starts centered and clamps to every display edge`() {
        val center = RemotePointerPosition()
        assertEquals(RemotePointerPosition(0.5, 0.5), center)
        assertEquals(
            RemotePointerPosition(1.0, 0.0),
            center.movedBy(RemotePointerDelta(0.75, -0.75)),
        )
        assertEquals(
            RemotePointerPosition(0.0, 1.0),
            center.movedBy(RemotePointerDelta(-0.75, 0.75)),
        )
    }

    @Test
    fun `burst movement coalesces to the latest absolute position`() {
        val coalescer = RemotePointerMoveCoalescer()

        assertTrue(coalescer.add(RemotePointerDelta(0.1, 0.0)))
        assertTrue(coalescer.add(RemotePointerDelta(0.0, -0.2)))
        assertEquals(0L, coalescer.delayUntilReady(1_000L))
        val emission = requireNotNull(coalescer.takeReady(1_000L))
        assertEquals(0.6, emission.position.x, 0.000_000_1)
        assertEquals(0.3, emission.position.y, 0.000_000_1)
        assertEquals(0.1, emission.delta.x, 0.000_000_1)
        assertEquals(-0.2, emission.delta.y, 0.000_000_1)
        assertFalse(coalescer.hasPendingMove())
        assertNull(coalescer.delayUntilReady(1_001L))
    }

    @Test
    fun `movement never becomes ready more often than one display frame`() {
        val coalescer = RemotePointerMoveCoalescer()
        coalescer.add(RemotePointerDelta(0.1, 0.0))
        assertEquals(
            RemotePointerPosition(0.6, 0.5),
            coalescer.takeReady(1_000L)?.position,
        )

        coalescer.add(RemotePointerDelta(0.1, 0.0))
        assertEquals(RemotePointerMoveCoalescer.MOVE_INTERVAL_MILLIS, coalescer.delayUntilReady(1_000L))
        assertNull(coalescer.takeReady(1_000L + RemotePointerMoveCoalescer.MOVE_INTERVAL_MILLIS - 1))
        assertEquals(0L, coalescer.delayUntilReady(1_000L + RemotePointerMoveCoalescer.MOVE_INTERVAL_MILLIS))
        assertEquals(
            RemotePointerPosition(0.7, 0.5),
            coalescer.takeReady(1_000L + RemotePointerMoveCoalescer.MOVE_INTERVAL_MILLIS)?.position,
        )
    }

    @Test
    fun `click consumes pending movement while link reset restores immediate eligibility`() {
        val coalescer = RemotePointerMoveCoalescer()
        coalescer.add(RemotePointerDelta(0.2, 0.1))
        coalescer.takeReady(100L)
        coalescer.add(RemotePointerDelta(0.1, 0.0))

        val latest = coalescer.takeLatest()
        assertEquals(0.8, latest.position.x, 0.000_000_1)
        assertEquals(0.6, latest.position.y, 0.000_000_1)
        assertEquals(0.1, latest.delta.x, 0.000_000_1)
        assertEquals(0.0, latest.delta.y, 0.000_000_1)
        assertFalse(coalescer.hasPendingMove())

        coalescer.add(RemotePointerDelta(-0.1, 0.0))
        coalescer.clearPending(resetRateLimit = true)
        coalescer.add(RemotePointerDelta(-0.1, 0.0))
        assertEquals(0L, coalescer.delayUntilReady(101L))
    }

    @Test
    fun `fallback position clamps but native movement remains available at an edge`() {
        val coalescer = RemotePointerMoveCoalescer()
        assertTrue(coalescer.add(RemotePointerDelta(1.0, 0.0)))
        coalescer.takeLatest()

        assertTrue(coalescer.add(RemotePointerDelta(0.1, 0.0)))
        val emission = coalescer.takeLatest()
        assertEquals(RemotePointerPosition(1.0, 0.5), emission.position)
        assertEquals(0.1, emission.delta.x, 0.000_000_1)
    }

    @Test
    fun `reset returns position and rate limit to a fresh centered stream`() {
        val coalescer = RemotePointerMoveCoalescer()
        coalescer.add(RemotePointerDelta(0.2, -0.1))
        coalescer.takeReady(1_000L)

        coalescer.reset()
        assertEquals(RemotePointerPosition(), coalescer.currentPosition())
        assertFalse(coalescer.hasPendingMove())
        coalescer.add(RemotePointerDelta(0.1, 0.0))
        assertEquals(0L, coalescer.delayUntilReady(1_001L))
    }
}
