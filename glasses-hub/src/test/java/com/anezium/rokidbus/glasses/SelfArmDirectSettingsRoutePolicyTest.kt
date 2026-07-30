package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmDirectSettingsRoutePolicyTest {
    @Test
    fun `accepted launch remains a probe until its verification window expires`() {
        assertFalse(
            SelfArmDirectSettingsRoutePolicy.shouldFallback(
                pending = true,
                startedAt = 1_000L,
                now = 2_799L,
                verificationWindowMs = 1_800L,
            ),
        )
        assertTrue(
            SelfArmDirectSettingsRoutePolicy.shouldFallback(
                pending = true,
                startedAt = 1_000L,
                now = 2_800L,
                verificationWindowMs = 1_800L,
            ),
        )
    }

    @Test
    fun `verified cancelled and unstarted routes never trigger a fallback`() {
        assertFalse(
            SelfArmDirectSettingsRoutePolicy.shouldFallback(
                pending = false,
                startedAt = 1_000L,
                now = 10_000L,
                verificationWindowMs = 1_800L,
            ),
        )
        assertFalse(
            SelfArmDirectSettingsRoutePolicy.shouldFallback(
                pending = true,
                startedAt = 0L,
                now = 10_000L,
                verificationWindowMs = 1_800L,
            ),
        )
    }
}
