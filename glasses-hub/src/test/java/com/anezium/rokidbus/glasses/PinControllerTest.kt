package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfacePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinControllerTest {
    private val content = PinSurfaceContent(
        title = "NEXUS PIN",
        lines = listOf("sample overlay"),
        position = PinSurfacePosition.TOP_RIGHT,
        ttlMs = null,
    )

    @Test
    fun `drops stale and duplicate show and hide sequences`() {
        val state = PinStateMachine()
        assertTrue(state.show("alpha:pin", 10L, content, 1_000L) is PinStateDecision.Applied)
        assertTrue(state.show("beta:pin", 10L, content, 1_001L) is PinStateDecision.DroppedStale)
        assertTrue(state.hide(9L) is PinStateDecision.DroppedStale)
        assertEquals("alpha:pin", state.activePin()?.surfaceId)

        assertTrue(state.hide(11L) is PinStateDecision.Hidden)
        assertNull(state.activePin())
    }

    @Test
    fun `newer show replaces the single global slot`() {
        val state = PinStateMachine()
        state.show("alpha:pin", 1L, content, 1_000L)
        state.show("beta:pin", 2L, content, 1_001L)
        assertEquals("beta:pin", state.activePin()?.surfaceId)
        assertEquals(2L, state.activePin()?.seq)
    }

    @Test
    fun `ttl expires only the matching active sequence`() {
        val state = PinStateMachine()
        val timed = content.copy(ttlMs = 1_000L)
        state.show("alpha:pin", 1L, timed, 5_000L)
        state.show("beta:pin", 2L, timed, 5_500L)

        assertTrue(!state.expire(6_000L, expectedSeq = 1L))
        assertEquals("beta:pin", state.activePin()?.surfaceId)
        assertTrue(state.expire(6_500L, expectedSeq = 2L))
        assertNull(state.activePin())
    }
}
