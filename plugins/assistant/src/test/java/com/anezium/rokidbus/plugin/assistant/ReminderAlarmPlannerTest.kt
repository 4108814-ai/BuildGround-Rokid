package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderAlarmPlannerTest {
    @Test
    fun `wall clock reminders plan rtc alarms`() {
        val plan = ReminderAlarmPlanner.plan(
            reminder = reminder(epochMillis = 2_000L),
            nowEpochMillis = 1_000L,
            nowElapsedRealtimeMillis = 50L,
        ) as AssistantAlarmPlan.Schedule

        assertEquals(AssistantAlarmClock.RTC, plan.clock)
        assertEquals(2_000L, plan.triggerAtMillis)
    }

    @Test
    fun `timers plan elapsed realtime alarms independent of wall clock`() {
        val plan = ReminderAlarmPlanner.plan(
            reminder = reminder(
                epochMillis = 2_000L,
                kind = AssistantReminderKind.TIMER,
                elapsedDeadline = 8_000L,
            ),
            nowEpochMillis = 99_000L,
            nowElapsedRealtimeMillis = 1_000L,
        ) as AssistantAlarmPlan.Schedule

        assertEquals(AssistantAlarmClock.ELAPSED_REALTIME, plan.clock)
        assertEquals(8_000L, plan.triggerAtMillis)
    }

    @Test
    fun `boot rebuilds a timer deadline from its remaining wall duration`() {
        val plan = ReminderAlarmPlanner.plan(
            reminder = reminder(
                epochMillis = 6_000L,
                kind = AssistantReminderKind.TIMER,
                elapsedDeadline = 99_000L,
            ),
            nowEpochMillis = 2_000L,
            nowElapsedRealtimeMillis = 500L,
            afterBoot = true,
        ) as AssistantAlarmPlan.Schedule

        assertEquals(AssistantAlarmClock.ELAPSED_REALTIME, plan.clock)
        assertEquals(4_500L, plan.triggerAtMillis)
    }

    @Test
    fun `restart plans classify passed entries for immediate late delivery`() {
        val plans = ReminderAlarmPlanner.plansAfterRestart(
            reminders = listOf(
                reminder(id = "r_aaaaaaaa", epochMillis = 999L),
                reminder(id = "r_bbbbbbbb", epochMillis = 2_000L),
            ),
            nowEpochMillis = 1_000L,
            nowElapsedRealtimeMillis = 100L,
            afterBoot = true,
        )

        assertTrue(plans[0] is AssistantAlarmPlan.DeliverImmediately)
        assertTrue(plans[1] is AssistantAlarmPlan.Schedule)
    }

    @Test
    fun `scheduler falls back to inexact and marks immediate restore as late`() {
        val gateway = RecordingGateway(exactUsed = false)
        val scheduler = DefaultAssistantReminderScheduler(
            gateway = gateway,
            canScheduleExact = { false },
            epochClock = { 1_000L },
            elapsedClock = { 100L },
        )

        val future = scheduler.schedule(reminder(epochMillis = 2_000L))
        val late = scheduler.schedule(
            reminder(id = "r_bbbbbbbb", epochMillis = 999L),
            afterBoot = true,
            lateIfImmediate = true,
        )

        assertFalse(future.exact)
        assertFalse(future.deliveredImmediately)
        assertTrue(late.deliveredImmediately)
        assertEquals(listOf("r_bbbbbbbb" to true), gateway.immediate)
    }

    private fun reminder(
        id: String = "r_aaaaaaaa",
        epochMillis: Long,
        kind: AssistantReminderKind = AssistantReminderKind.REMINDER,
        elapsedDeadline: Long? = null,
    ) = AssistantReminder(
        id = id,
        label = "Test",
        epochMillis = epochMillis,
        originalIso = "time",
        createdAtMs = 0L,
        kind = kind,
        elapsedRealtimeDeadlineMs = elapsedDeadline,
    )

    private class RecordingGateway(
        private val exactUsed: Boolean,
    ) : ReminderAlarmGateway {
        val immediate = mutableListOf<Pair<String, Boolean>>()

        override fun schedule(
            id: String,
            clock: AssistantAlarmClock,
            triggerAtMillis: Long,
            exactRequested: Boolean,
        ): Boolean = exactUsed

        override fun cancel(id: String) = Unit

        override fun deliverNow(id: String, late: Boolean) {
            immediate += id to late
        }
    }
}
