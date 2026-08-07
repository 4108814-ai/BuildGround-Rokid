package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayWakePolicyTest {
    @Test
    fun `decision matrix covers every kind request display and budget state`() {
        val now = 20_000L
        val budgets = listOf(
            DisplayWakeBudget(),
            DisplayWakeBudget(lastWakeAtMs = now - DisplayWakePolicy.BUDGET_WINDOW_MS),
            DisplayWakeBudget(lastWakeAtMs = now - DisplayWakePolicy.BUDGET_WINDOW_MS + 1L),
        )

        DisplayWakeKind.entries.forEach { kind ->
            listOf(false, true).forEach { requested ->
                listOf(false, true).forEach { interactive ->
                    budgets.forEach { budget ->
                        val decision = DisplayWakePolicy.decide(
                            kind = kind,
                            requested = requested,
                            isInteractive = interactive,
                            budget = budget,
                            nowMs = now,
                        )
                        when {
                            !requested -> assertRefused(
                                decision,
                                DisplayWakeRefusal.NOT_REQUESTED,
                                budget,
                            )
                            interactive -> assertRefused(
                                decision,
                                DisplayWakeRefusal.ALREADY_INTERACTIVE,
                                budget,
                            )
                            budget.lastWakeAtMs != null &&
                                now - budget.lastWakeAtMs < DisplayWakePolicy.BUDGET_WINDOW_MS ->
                                assertRefused(
                                    decision,
                                    DisplayWakeRefusal.BUDGET_EXHAUSTED,
                                    budget,
                                )
                            else -> {
                                assertTrue(decision is DisplayWakeDecision.Wake)
                                assertEquals(DisplayWakeBudget(now), decision.budget)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an interactive display leaves the budget for the next dark event`() {
        val budget = DisplayWakeBudget(lastWakeAtMs = 1_000L)
        val unnecessary = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = true,
            budget = budget,
            nowMs = 4_000L,
        )
        assertRefused(unnecessary, DisplayWakeRefusal.ALREADY_INTERACTIVE, budget)

        val dark = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = unnecessary.budget,
            nowMs = 6_000L,
        )
        assertTrue(dark is DisplayWakeDecision.Wake)
        assertEquals(DisplayWakeBudget(6_000L), dark.budget)
    }

    @Test
    fun `a surface push and notice show spend the same global budget`() {
        val surface = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.SURFACE,
            requested = true,
            isInteractive = false,
            budget = DisplayWakeBudget(),
            nowMs = 1_000L,
        )
        assertTrue(surface is DisplayWakeDecision.Wake)

        val blockedNotice = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = surface.budget,
            nowMs = 5_999L,
        )
        assertRefused(
            blockedNotice,
            DisplayWakeRefusal.BUDGET_EXHAUSTED,
            surface.budget,
        )

        val admittedNotice = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = blockedNotice.budget,
            nowMs = 6_000L,
        )
        assertTrue(admittedNotice is DisplayWakeDecision.Wake)
    }

    @Test
    fun `a fresh notice receives one successor wake inside the global window`() {
        val first = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = DisplayWakeBudget(),
            nowMs = 1_000L,
            newNotice = true,
        ) as DisplayWakeDecision.Wake
        assertEquals(DisplayWakeAdmission.BUDGET_AVAILABLE, first.admission)
        assertEquals(0, first.budget.unattendedNoticeWakeCount)

        val successor = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = first.budget,
            nowMs = 4_500L,
            newNotice = true,
        ) as DisplayWakeDecision.Wake

        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, successor.admission)
        assertEquals(1, successor.budget.unattendedNoticeWakeCount)
        assertEquals(4_500L, successor.budget.lastNoticeWakeAtMs)
    }

    @Test
    fun `spaced messages keep waking exactly as before the entitlement existed`() {
        var budget = DisplayWakeBudget()
        var at = 1_000L
        repeat(5) {
            val wake = freshNoticeWake(budget, nowMs = at)
            assertEquals(DisplayWakeAdmission.BUDGET_AVAILABLE, wake.admission)
            assertEquals(0, wake.budget.unattendedNoticeWakeCount)
            budget = wake.budget
            at += 30_000L
        }
    }

    @Test
    fun `updates cannot use the fresh notice entitlement`() {
        val first = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = DisplayWakeBudget(),
            nowMs = 1_000L,
            newNotice = true,
        ) as DisplayWakeDecision.Wake

        val update = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = first.budget,
            nowMs = 1_100L,
            newNotice = false,
        )

        assertRefused(update, DisplayWakeRefusal.BUDGET_EXHAUSTED, first.budget)
    }

    @Test
    fun `two entitlement wakes cap a hot burst until its quiet reset`() {
        val first = freshNoticeWake(DisplayWakeBudget(), nowMs = 1_000L)
        val second = freshNoticeWake(first.budget, nowMs = 4_500L)
        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, second.admission)
        val third = freshNoticeWake(second.budget, nowMs = 8_000L)
        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, third.admission)
        assertEquals(2, third.budget.unattendedNoticeWakeCount)

        val blocked = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = false,
            budget = third.budget,
            nowMs = 11_000L,
            newNotice = true,
        )
        assertRefused(blocked, DisplayWakeRefusal.NOTICE_EPISODE_LIMIT, third.budget)

        val afterQuiet = freshNoticeWake(
            third.budget,
            nowMs = 8_000L + DisplayWakePolicy.NOTICE_EPISODE_RESET_MS,
        )
        assertEquals(0, afterQuiet.budget.unattendedNoticeWakeCount)
        assertEquals(DisplayWakeAdmission.BUDGET_AVAILABLE, afterQuiet.admission)
    }

    @Test
    fun `real user input resets only the unattended notice count`() {
        val first = freshNoticeWake(DisplayWakeBudget(), nowMs = 1_000L)
        val second = freshNoticeWake(first.budget, nowMs = 4_500L)

        val reset = DisplayWakePolicy.afterUserInteraction(second.budget)

        assertEquals(second.budget.lastWakeAtMs, reset.lastWakeAtMs)
        assertEquals(0, reset.unattendedNoticeWakeCount)
        assertNull(reset.lastNoticeWakeAtMs)
        val next = freshNoticeWake(reset, nowMs = 4_600L)
        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, next.admission)
        assertEquals(1, next.budget.unattendedNoticeWakeCount)
    }

    @Test
    fun `already interactive during lock can be reevaluated once dark`() {
        val first = freshNoticeWake(DisplayWakeBudget(), nowMs = 1_000L)
        val duringLock = DisplayWakePolicy.decide(
            kind = DisplayWakeKind.NOTICE,
            requested = true,
            isInteractive = true,
            budget = first.budget,
            nowMs = 4_500L,
            newNotice = true,
        )
        assertRefused(duringLock, DisplayWakeRefusal.ALREADY_INTERACTIVE, first.budget)

        val afterLock = freshNoticeWake(duringLock.budget, nowMs = 4_575L)

        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, afterLock.admission)
        assertEquals(1, afterLock.budget.unattendedNoticeWakeCount)
    }

    private fun freshNoticeWake(
        budget: DisplayWakeBudget,
        nowMs: Long,
    ): DisplayWakeDecision.Wake = DisplayWakePolicy.decide(
        kind = DisplayWakeKind.NOTICE,
        requested = true,
        isInteractive = false,
        budget = budget,
        nowMs = nowMs,
        newNotice = true,
    ) as DisplayWakeDecision.Wake

    private fun assertRefused(
        decision: DisplayWakeDecision,
        reason: DisplayWakeRefusal,
        unchangedBudget: DisplayWakeBudget,
    ) {
        assertTrue(decision is DisplayWakeDecision.Refused)
        assertEquals(reason, (decision as DisplayWakeDecision.Refused).reason)
        assertEquals(unchangedBudget, decision.budget)
    }
}
