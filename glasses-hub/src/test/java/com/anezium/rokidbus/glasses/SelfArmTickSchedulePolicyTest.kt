package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfArmTickSchedulePolicyTest {
    @Test
    fun `first request schedules a tick`() {
        assertEquals(
            1_200L,
            SelfArmTickSchedulePolicy.nextScheduledAt(
                existingAt = SelfArmTickSchedulePolicy.NONE,
                requestedAt = 1_200L,
            ),
        )
    }

    @Test
    fun `event can wake earlier`() {
        assertEquals(
            800L,
            SelfArmTickSchedulePolicy.nextScheduledAt(
                existingAt = 1_200L,
                requestedAt = 800L,
            ),
        )
    }

    @Test
    fun `continuous later events cannot starve existing tick`() {
        var scheduledAt = 1_000L
        repeat(100) { index ->
            scheduledAt = SelfArmTickSchedulePolicy.nextScheduledAt(
                existingAt = scheduledAt,
                requestedAt = 1_001L + index,
            )
        }

        assertEquals(1_000L, scheduledAt)
    }
}
