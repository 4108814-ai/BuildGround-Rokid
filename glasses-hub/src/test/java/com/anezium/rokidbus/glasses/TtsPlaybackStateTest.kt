package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.TtsDoneReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsPlaybackStateTest {
    @Test
    fun `start and natural stop emit once for the current utterance`() {
        val state = TtsPlaybackState()
        state.accept("alpha", "u1", "engine-1", "hello")

        assertEquals(TtsStartedEvent("alpha", "u1"), state.started("engine-1"))
        assertNull(state.started("engine-1"))
        assertEquals(
            TtsDoneEvent("alpha", "u1", TtsDoneReason.COMPLETED),
            state.stopped("engine-1"),
        )
        assertNull(state.stopped("engine-1"))
        assertNull(state.unavailable())
    }

    @Test
    fun `a new speak preempts exactly the old utterance and ignores its late callbacks`() {
        val state = TtsPlaybackState()
        state.accept("alpha", "u1", "engine-1", "one")
        val second = state.accept("beta", "u2", "engine-2", "two")

        assertEquals(
            TtsDoneEvent("alpha", "u1", TtsDoneReason.PREEMPTED),
            second.preempted,
        )
        assertNull(state.started("engine-1"))
        assertNull(state.stopped("engine-1"))
        assertEquals(TtsStartedEvent("beta", "u2"), state.started("engine-2"))
        assertEquals(
            TtsDoneEvent("beta", "u2", TtsDoneReason.COMPLETED),
            state.stopped("engine-2"),
        )
    }

    @Test
    fun `only the owner can stop and stop maps the callback to STOPPED`() {
        val state = TtsPlaybackState()
        state.accept("alpha", "shared", "engine-1", "hello")

        assertNull(state.requestStop("beta", "shared"))
        assertNull(state.requestStop("alpha", "wrong"))
        assertEquals(
            "engine-1",
            state.requestStop("alpha", "shared")?.engineId,
        )
        assertNull(state.requestStop("alpha", "shared"))
        assertEquals(
            TtsDoneEvent("alpha", "shared", TtsDoneReason.STOPPED),
            state.stopped("engine-1"),
        )
    }

    @Test
    fun `controller cancellation seam reports platform cancellation without an owner lookup`() {
        val state = TtsPlaybackState()
        assertNull(state.cancelCurrent(TtsDoneReason.CANCELLED))
        state.accept("alpha", "u1", "engine-1", "hello")

        assertEquals("engine-1", state.cancelCurrent(TtsDoneReason.CANCELLED)?.engineId)
        assertNull(state.cancelCurrent(TtsDoneReason.CANCELLED))
        assertEquals(
            TtsDoneEvent("alpha", "u1", TtsDoneReason.CANCELLED),
            state.stopped("engine-1"),
        )
        assertNull(state.stopped("engine-1"))
    }

    @Test
    fun `service loss overrides an in-flight stop and remains exactly once`() {
        val state = TtsPlaybackState()
        state.accept("alpha", "u1", "engine-1", "hello")
        state.requestStop("alpha", "u1")

        assertEquals(
            TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE),
            state.unavailable(),
        )
        assertNull(state.unavailable())
        assertNull(state.stopped("engine-1"))
    }

    @Test
    fun `done outbox retries in order without duplicating accepted sends`() {
        val outbox = TtsDoneOutbox()
        val first = TtsDoneEvent("alpha", "u1", TtsDoneReason.PREEMPTED)
        val second = TtsDoneEvent("beta", "u2", TtsDoneReason.COMPLETED)
        outbox.enqueue(first)
        outbox.enqueue(second)
        val attempts = mutableListOf<TtsDoneEvent>()

        outbox.flush { event ->
            attempts += event
            false
        }
        assertEquals(listOf(first), attempts)
        assertEquals(2, outbox.size())

        outbox.flush { event ->
            attempts += event
            true
        }
        assertEquals(listOf(first, first, second), attempts)
        assertEquals(0, outbox.size())

        outbox.flush { event ->
            attempts += event
            true
        }
        assertEquals(listOf(first, first, second), attempts)
    }
}
