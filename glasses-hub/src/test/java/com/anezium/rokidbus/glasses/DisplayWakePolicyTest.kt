package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
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
