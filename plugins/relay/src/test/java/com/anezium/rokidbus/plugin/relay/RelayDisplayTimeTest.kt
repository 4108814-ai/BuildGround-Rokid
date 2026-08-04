package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDisplayTimeTest {
    @Test
    fun `auto omits an explicit notice ttl while fixed durations use milliseconds`() {
        assertNull(RelayNoticeRuntime.explicitNoticeTtlMs(0))
        assertEquals(5_000L, RelayNoticeRuntime.explicitNoticeTtlMs(5))
        assertEquals(45_000L, RelayNoticeRuntime.explicitNoticeTtlMs(45))
    }

    @Test
    fun `display time coercion keeps auto and snaps arbitrary values to valid durations`() {
        (0..45 step 5).forEach { value ->
            assertEquals(value, RelaySettings.coerceNoticeDisplaySeconds(value))
        }
        assertEquals(5, RelaySettings.coerceNoticeDisplaySeconds(-1))
        assertEquals(5, RelaySettings.coerceNoticeDisplaySeconds(4))
        assertEquals(10, RelaySettings.coerceNoticeDisplaySeconds(12))
        assertEquals(15, RelaySettings.coerceNoticeDisplaySeconds(13))
        assertEquals(45, RelaySettings.coerceNoticeDisplaySeconds(46))
    }

    @Test
    fun `display time stepping crosses the auto boundary and stops at the ceiling`() {
        assertEquals(5, RelaySettings.stepNoticeDisplaySeconds(0, 1))
        assertEquals(0, RelaySettings.stepNoticeDisplaySeconds(5, -1))
        assertEquals(0, RelaySettings.stepNoticeDisplaySeconds(0, -1))
        assertEquals(20, RelaySettings.stepNoticeDisplaySeconds(15, 1))
        assertEquals(10, RelaySettings.stepNoticeDisplaySeconds(15, -1))
        assertEquals(45, RelaySettings.stepNoticeDisplaySeconds(45, 1))
    }
}
