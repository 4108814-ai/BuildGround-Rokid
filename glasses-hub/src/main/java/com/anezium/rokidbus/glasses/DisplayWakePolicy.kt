package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Every source that may ask to relight the one physical display.
 *
 * These are the HUD tiers, and only them. Setup flows the wearer is actively
 * reading — manual pairing, the camera viewfinder, the launcher, an engaged
 * surface — normally hold the screen through their own window flags and are
 * deliberately not routed through this one-shot policy. The assistant's
 * notice-to-Ink episode is the measured firmware exception and uses the
 * separate [AssistantDisplayEpisode] owner.
 */
internal enum class DisplayWakeKind(val logValue: String) {
    SURFACE("surface"),
    NOTICE("notice"),
    ACTIVITY("activity"),
}

/** The entire global budget: there is deliberately no plugin or kind key. */
internal data class DisplayWakeBudget(
    val lastWakeAtMs: Long? = null,
    val unattendedNoticeWakeCount: Int = 0,
    val lastNoticeWakeAtMs: Long? = null,
)

internal enum class DisplayWakeAdmission(val logValue: String) {
    BUDGET_AVAILABLE("budget_available"),
    NEW_NOTICE_ENTITLEMENT("new_notice_entitlement"),
}

internal enum class DisplayWakeRefusal(val logValue: String) {
    NOT_REQUESTED("not_requested"),
    ALREADY_INTERACTIVE("already_interactive"),
    BUDGET_EXHAUSTED("budget_exhausted"),
    NOTICE_EPISODE_LIMIT("notice_episode_limit"),
    POWER_SERVICE_UNAVAILABLE("power_unavailable"),
    ACQUIRE_FAILED("acquire_failed"),
}

internal sealed interface DisplayWakeDecision {
    val kind: DisplayWakeKind
    val budget: DisplayWakeBudget

    data class Wake(
        override val kind: DisplayWakeKind,
        val admission: DisplayWakeAdmission,
        override val budget: DisplayWakeBudget,
    ) : DisplayWakeDecision

    data class Refused(
        override val kind: DisplayWakeKind,
        val reason: DisplayWakeRefusal,
        override val budget: DisplayWakeBudget,
    ) : DisplayWakeDecision
}

/**
 * The single owner of one-shot display wake decisions and budget state.
 * Assistant conversations use [AssistantDisplayEpisode], whose renewable hold
 * is intentionally separate from this older rate-limited wake path.
 */
internal object DisplayWakePolicy {
    const val BUDGET_WINDOW_MS = 5_000L
    const val WAKE_LOCK_MS = 3_000L
    const val MAX_UNATTENDED_NOTICE_WAKE_EPISODES = 2
    const val NOTICE_EPISODE_RESET_MS = 60_000L

    private var currentBudget = DisplayWakeBudget()

    fun decide(
        kind: DisplayWakeKind,
        requested: Boolean,
        isInteractive: Boolean,
        budget: DisplayWakeBudget,
        nowMs: Long,
        newNotice: Boolean = false,
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
        // A cold budget wakes exactly as it always has; the episode limit only
        // bounds the new-notice entitlement, which is what lets a fresh notice
        // wake while the budget is still hot. Counting cold wakes too would
        // darken the third of three messages spaced half a minute apart — the
        // very miss this entitlement exists to fix.
        budgetIsHot(budget, nowMs) && !(newNotice && kind == DisplayWakeKind.NOTICE) ->
            DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.BUDGET_EXHAUSTED,
                budget,
            )
        budgetIsHot(budget, nowMs) &&
            unattendedNoticeWakeCount(budget, nowMs) >= MAX_UNATTENDED_NOTICE_WAKE_EPISODES ->
            DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.NOTICE_EPISODE_LIMIT,
                budget,
            )
        else -> {
            val entitled = budgetIsHot(budget, nowMs)
            DisplayWakeDecision.Wake(
                kind = kind,
                admission = if (entitled) {
                    DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT
                } else {
                    DisplayWakeAdmission.BUDGET_AVAILABLE
                },
                budget = budget.copy(
                    lastWakeAtMs = nowMs,
                    unattendedNoticeWakeCount = if (entitled) {
                        unattendedNoticeWakeCount(budget, nowMs) + 1
                    } else {
                        unattendedNoticeWakeCount(budget, nowMs)
                    },
                    lastNoticeWakeAtMs = if (entitled) nowMs else budget.lastNoticeWakeAtMs,
                ),
            )
        }
    }

    private fun budgetIsHot(budget: DisplayWakeBudget, nowMs: Long): Boolean =
        budget.lastWakeAtMs?.let { nowMs - it < BUDGET_WINDOW_MS } == true

    internal fun afterUserInteraction(budget: DisplayWakeBudget): DisplayWakeBudget = budget.copy(
        unattendedNoticeWakeCount = 0,
        lastNoticeWakeAtMs = null,
    )

    @Synchronized
    fun noteUserInteraction() {
        currentBudget = afterUserInteraction(currentBudget)
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun requestWake(
        context: Context,
        kind: DisplayWakeKind,
        requested: Boolean,
        seq: Long? = null,
        newNotice: Boolean = false,
    ): DisplayWakeDecision {
        val previousBudget = currentBudget
        val nowMs = SystemClock.elapsedRealtime()
        val power = context.getSystemService(PowerManager::class.java)
            ?: return DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.POWER_SERVICE_UNAVAILABLE,
                currentBudget,
            ).also { decision ->
                logDecision(
                    seq = seq,
                    decision = decision,
                    interactive = null,
                    nowMs = nowMs,
                    previousBudget = previousBudget,
                )
            }
        val interactive = power.isInteractive
        val decision = decide(
            kind = kind,
            requested = requested,
            isInteractive = interactive,
            budget = currentBudget,
            nowMs = nowMs,
            newNotice = newNotice,
        )
        if (decision is DisplayWakeDecision.Refused) {
            logDecision(seq, decision, interactive, nowMs, previousBudget)
            return decision
        }

        return runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rokidbus:display-wake",
            ).acquire(WAKE_LOCK_MS)
            currentBudget = decision.budget
            logDecision(seq, decision, interactive, nowMs, previousBudget)
            decision
        }.getOrElse { error ->
            logError("Display wake failed kind=${kind.logValue}", error)
            DisplayWakeDecision.Refused(
                kind,
                DisplayWakeRefusal.ACQUIRE_FAILED,
                currentBudget,
            ).also { refused ->
                logDecision(seq, refused, interactive, nowMs, previousBudget)
            }
        }
    }

    private fun unattendedNoticeWakeCount(
        budget: DisplayWakeBudget,
        nowMs: Long,
    ): Int = if (
        budget.lastNoticeWakeAtMs == null ||
        nowMs - budget.lastNoticeWakeAtMs >= NOTICE_EPISODE_RESET_MS
    ) {
        0
    } else {
        budget.unattendedNoticeWakeCount
    }

    private fun logDecision(
        seq: Long?,
        decision: DisplayWakeDecision,
        interactive: Boolean?,
        nowMs: Long,
        previousBudget: DisplayWakeBudget,
    ) {
        val lastWakeAgeMs = previousBudget.lastWakeAtMs
            ?.let { (nowMs - it).coerceAtLeast(0L) }
            ?: -1L
        val (value, reason) = when (decision) {
            is DisplayWakeDecision.Wake -> "wake" to decision.admission.logValue
            is DisplayWakeDecision.Refused -> "refused" to decision.reason.logValue
        }
        log(
            "wake seq=${seq ?: -1L} decision=$value reason=$reason " +
                "interactive=${interactive ?: "unknown"} lastWakeAgeMs=$lastWakeAgeMs " +
                "kind=${decision.kind.logValue}",
        )
    }
}
