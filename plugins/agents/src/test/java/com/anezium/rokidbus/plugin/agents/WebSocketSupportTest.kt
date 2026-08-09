package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketSupportTest {
    @Test
    fun supersededGenerationCannotClearNewResource() {
        val slot = GenerationSlot<String>()
        val first = slot.advance()
        assertTrue(slot.install(first.generation, "old"))

        val second = slot.advance()
        assertEquals("old", second.previous)
        assertTrue(slot.install(second.generation, "new"))
        assertFalse(slot.install(first.generation, "late"))
        assertNull(slot.clear(first.generation))
        assertEquals("new", slot.current())
        assertEquals("new", slot.clear(second.generation))
    }

    @Test
    fun successfulHandshakeResetReturnsBackoffToFirstTier() {
        val attempts = mutableListOf<Int>()
        val backoff = ReconnectBackoff { attempt ->
            attempts += attempt
            attempt.toLong()
        }

        assertEquals(0L, backoff.nextDelayMs())
        assertEquals(1L, backoff.nextDelayMs())
        assertEquals(2L, backoff.nextDelayMs())
        backoff.reset()
        assertEquals(0L, backoff.nextDelayMs())
        assertEquals(listOf(0, 1, 2, 0), attempts)
    }

    @Test
    fun armedDeadlineFiresAndClearedDeadlineDoesNot() = runBlocking {
        val fired = CompletableDeferred<String>()
        val deadlines = ConnectionDeadlines(this, timeoutMs = 30L) {
            fired.complete(it)
        }
        deadlines.arm("hello", "hello timed out")
        assertEquals("hello timed out", withTimeout(1_000L) { fired.await() })

        var cancelledFired = false
        val cancelled = ConnectionDeadlines(this, timeoutMs = 30L) {
            cancelledFired = true
        }
        cancelled.arm("list", "list timed out")
        cancelled.clear("list")
        delay(100L)
        assertFalse(cancelledFired)
    }
}
