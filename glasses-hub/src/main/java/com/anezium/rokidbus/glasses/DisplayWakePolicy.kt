package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Every source that may ask to relight the one physical display.
 *
 * These are the HUD tiers, and only them. Setup flows the wearer is actively
 * reading — manual pairing, the camera viewfinder, the launcher, an engaged
 * surface — hold the screen through their own window flags and are deliberately
 * not routed here: they are not events interrupting a wearer, they are the
 * wearer looking at something.
 */
internal enum class DisplayWakeKind(val logValue: String) {
    SURFACE("surface"),
    NOTICE("notice"),
    ACTIVITY("activity"),
}

/** The entire global budget: there is deliberately no plugin or kind key. */
internal data class DisplayWakeBudget(val lastWakeAtMs: Long? = null)

internal enum class DisplayWakeRefusal {
    NOT_REQUESTED,
    ALREADY_INTERACTIVE,
    BUDGET_EXHAUSTED,
    POWER_SERVICE_UNAVAILABLE,
    ACQUIRE_FAILED,
}

internal sealed interface DisplayWakeDecision {
    val kind: DisplayWakeKind
    val budget: DisplayWakeBudget

    data class Wake(
        override val kind: DisplayWakeKind,
        override val budget: DisplayWakeBudget,
    ) : DisplayWakeDecision

    data class Refused(
        override val kind: DisplayWakeKind,
        val reason: DisplayWakeRefusal,
        override val budget: DisplayWakeBudget,
    ) : DisplayWakeDecision
}

/**
 * The single owner of display wake decisions, budget state, and lock acquisition.
 *
 * [decide] is the complete pure policy. [requestWake] is its Android edge: it
 * reads the display state, acquires the existing three-second lock only after a
 * `Wake` decision, and spends the budget only after acquisition succeeds.
 */
internal object DisplayWakePolicy {
    const val BUDGET_WINDOW_MS = 5_000L
    const val WAKE_LOCK_MS = 3_000L

    private var currentBudget = DisplayWakeBudget()

    fun decide(
        kind: DisplayWakeKind,
        requested: Boolean,
        isInteractive: Boolean,
        budget: DisplayWakeBudget,
        nowMs: Long,
    ): DisplayWakeDecision = when {
        !requested -> DisplayWakeDecision.Refused(
            kind,
            DisplayWakeRefusal.NOT_REQUESTED,
            budget,
        )
        isInteractive -> DisplayWakeDecision.Refused(
            kind,
            DisplayWakeRefusal.ALREADY_INTERACTIVE,
            budget,
        )
        budget.lastWakeAtMs?.let { nowMs - it < BUDGET_WINDOW_MS } == true ->
            DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.BUDGET_EXHAUSTED,
                budget,
            )
        else -> DisplayWakeDecision.Wake(kind, DisplayWakeBudget(lastWakeAtMs = nowMs))
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun requestWake(
        context: Context,
        kind: DisplayWakeKind,
        requested: Boolean,
    ): DisplayWakeDecision {
        val power = context.getSystemService(PowerManager::class.java)
            ?: return DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.POWER_SERVICE_UNAVAILABLE,
                currentBudget,
            ).also { log("display wake refused kind=${kind.logValue} reason=power_unavailable") }
        val decision = decide(
            kind = kind,
            requested = requested,
            isInteractive = power.isInteractive,
            budget = currentBudget,
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (decision is DisplayWakeDecision.Refused) {
            if (decision.reason == DisplayWakeRefusal.BUDGET_EXHAUSTED) {
                log("display wake refused kind=${kind.logValue} reason=budget")
            }
            return decision
        }

        return runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rokidbus:display-wake",
            ).acquire(WAKE_LOCK_MS)
            currentBudget = decision.budget
            log("display wake acquired kind=${kind.logValue}")
            decision
        }.getOrElse { error ->
            logError("Display wake failed kind=${kind.logValue}", error)
            DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.ACQUIRE_FAILED,
                currentBudget,
            )
        }
    }
}
