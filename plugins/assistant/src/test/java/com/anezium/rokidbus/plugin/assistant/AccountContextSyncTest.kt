package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountContextSyncTest {
    @Test
    fun combineLogicPutsSyncedAccountContextBeforeManualMemory() {
        assertEquals(
            "Synced account context.\n\nManual notes.",
            combineAccountContextForPrompt(
                syncEnabled = true,
                syncedAccountContext = "Synced account context.",
                assistantMemory = "Manual notes.",
            ),
        )
    }

    @Test
    fun combineLogicUsesManualMemoryOnlyWhenSyncIsOff() {
        assertEquals(
            "Manual notes.",
            combineAccountContextForPrompt(
                syncEnabled = false,
                syncedAccountContext = "Synced account context.",
                assistantMemory = "Manual notes.",
            ),
        )
    }

    @Test
    fun combineLogicReturnsEmptyWhenBothSourcesAreEmpty() {
        assertEquals(
            "",
            combineAccountContextForPrompt(
                syncEnabled = true,
                syncedAccountContext = " \n ",
                assistantMemory = "",
            ),
        )
    }

    @Test
    fun staleLogicHandlesMissingExpiredFreshAndFutureTimestamps() {
        val now = 1_000_000L
        val ttl = 10_000L

        assertTrue(isAccountContextCacheStale(0L, now, ttl))
        assertTrue(isAccountContextCacheStale(now - ttl, now, ttl))
        assertFalse(isAccountContextCacheStale(now - ttl + 1L, now, ttl))
        assertTrue(isAccountContextCacheStale(now + 1L, now, ttl))
    }
}
