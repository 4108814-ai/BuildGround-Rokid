package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
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

internal enum class DisplayHoldReleaseReason(val logValue: String) {
    NOTICE_USER("notice_user"),
    NOTICE_TIMEOUT("notice_timeout"),
    NOTICE_OWNER("notice_owner"),
    NOTICE_REPLACED("notice_replaced"),
    NOTICE_DISCONNECT("notice_disconnect"),
    LINK_LOSS("link_loss"),
    SERVICE_DESTROYED("service_destroyed"),
    SESSION_CLOSED("session_closed"),
    SURFACE_HIDDEN("surface_hidden"),
}

internal enum class DisplayHoldPhase {
    HELD,
    SUSPENDED,
    CEILING,
}

internal data class DisplayHoldSnapshot(
    val ownerId: String,
    val startedAtMs: Long,
    val lastRenewedAtMs: Long,
    val lastSeq: Long,
    val phase: DisplayHoldPhase,
)

internal enum class DisplayHoldFailure(val logValue: String) {
    POWER_SERVICE_UNAVAILABLE("power_unavailable"),
    ACQUIRE_FAILED("acquire_failed"),
}

internal sealed interface DisplayHoldTransition {
    val ownerId: String
    val seq: Long
    val ageMs: Long

    data class Acquire(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val leaseMs: Long,
        val resumed: Boolean,
    ) : DisplayHoldTransition

    data class Renew(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val leaseMs: Long,
    ) : DisplayHoldTransition

    data class Release(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val reason: DisplayHoldReleaseReason,
        val lockWasHeld: Boolean,
    ) : DisplayHoldTransition

    data class Ceiling(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val lockWasHeld: Boolean,
    ) : DisplayHoldTransition

    data class Refused(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val reason: DisplayHoldFailure,
    ) : DisplayHoldTransition
}

internal interface DisplayHoldLease {
    fun acquire(timeoutMs: Long)

    fun release()
}

/**
 * A renewable, explicitly-ended display hold with one absolute episode ceiling.
 *
 * This class has no Android dependencies so the complete lifecycle can be
 * exercised in plain unit tests. A suspended episode remembers its original
 * start: recreating the accessibility service can resume the same episode, but
 * can never buy it another safety window.
 */
internal class DisplayHoldLifecycle(
    private val ceilingMs: Long,
) {
    private data class Episode(
        var snapshot: DisplayHoldSnapshot,
        var lease: DisplayHoldLease? = null,
    )

    private var episode: Episode? = null

    fun snapshot(): DisplayHoldSnapshot? = episode?.snapshot

    fun begin(
        ownerId: String,
        seq: Long,
        requested: Boolean,
        nowMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): List<DisplayHoldTransition> {
        val transitions = mutableListOf<DisplayHoldTransition>()
        end(DisplayHoldReleaseReason.NOTICE_REPLACED, nowMs)?.let(transitions::add)
        if (!requested) return transitions

        episode = Episode(
            DisplayHoldSnapshot(
                ownerId = ownerId,
                startedAtMs = nowMs,
                lastRenewedAtMs = nowMs,
                lastSeq = seq,
                phase = DisplayHoldPhase.SUSPENDED,
            ),
        )
        transitions += acquire(seq, nowMs, resumed = false, leaseFactory)
        return transitions
    }

    fun renew(
        ownerId: String,
        seq: Long,
        eligibleToStart: Boolean,
        nowMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): DisplayHoldTransition? {
        val current = episode
        if (current == null) {
            if (!eligibleToStart) return null
            episode = Episode(
                DisplayHoldSnapshot(
                    ownerId = ownerId,
                    startedAtMs = nowMs,
                    lastRenewedAtMs = nowMs,
                    lastSeq = seq,
                    phase = DisplayHoldPhase.SUSPENDED,
                ),
            )
            return acquire(seq, nowMs, resumed = false, leaseFactory)
        }
        if (current.snapshot.ownerId != ownerId) return null
        if (nowMs - current.snapshot.startedAtMs >= ceilingMs) {
            return enforceCeiling(ownerId, current.snapshot.startedAtMs, nowMs)
        }
        if (current.snapshot.phase == DisplayHoldPhase.CEILING) return null

        return if (current.snapshot.phase == DisplayHoldPhase.SUSPENDED) {
            acquire(seq, nowMs, resumed = true, leaseFactory)
        } else {
            renewHeld(seq, nowMs)
        }
    }

    fun suspend(
        reason: DisplayHoldReleaseReason,
        nowMs: Long,
    ): DisplayHoldTransition.Release? {
        val current = episode ?: return null
        if (current.snapshot.phase != DisplayHoldPhase.HELD) return null
        releaseLease(current)
        current.snapshot = current.snapshot.copy(phase = DisplayHoldPhase.SUSPENDED)
        return DisplayHoldTransition.Release(
            ownerId = current.snapshot.ownerId,
            seq = current.snapshot.lastSeq,
            ageMs = age(current, nowMs),
            reason = reason,
            lockWasHeld = true,
        )
    }

    fun end(
        reason: DisplayHoldReleaseReason,
        nowMs: Long,
    ): DisplayHoldTransition.Release? {
        val current = episode ?: return null
        val wasHeld = current.snapshot.phase == DisplayHoldPhase.HELD
        if (wasHeld) releaseLease(current)
        episode = null
        return DisplayHoldTransition.Release(
            ownerId = current.snapshot.ownerId,
            seq = current.snapshot.lastSeq,
            ageMs = age(current, nowMs),
            reason = reason,
            lockWasHeld = wasHeld,
        )
    }

    fun enforceCeiling(
        ownerId: String,
        startedAtMs: Long,
        nowMs: Long,
    ): DisplayHoldTransition.Ceiling? {
        val current = episode ?: return null
        if (
            current.snapshot.ownerId != ownerId ||
            current.snapshot.startedAtMs != startedAtMs ||
            nowMs - startedAtMs < ceilingMs ||
            current.snapshot.phase == DisplayHoldPhase.CEILING
        ) {
            return null
        }
        val wasHeld = current.snapshot.phase == DisplayHoldPhase.HELD
        if (wasHeld) releaseLease(current)
        current.snapshot = current.snapshot.copy(phase = DisplayHoldPhase.CEILING)
        return DisplayHoldTransition.Ceiling(
            ownerId = ownerId,
            seq = current.snapshot.lastSeq,
            ageMs = age(current, nowMs),
            lockWasHeld = wasHeld,
        )
    }

    private fun acquire(
        seq: Long,
        nowMs: Long,
        resumed: Boolean,
        leaseFactory: () -> DisplayHoldLease?,
    ): DisplayHoldTransition {
        val current = requireNotNull(episode)
        val lease = current.lease ?: runCatching(leaseFactory).getOrNull()
            ?: return refused(current, seq, nowMs, DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE)
        current.lease = lease
        val remainingMs = remaining(current, nowMs)
        return runCatching {
            lease.acquire(remainingMs)
            current.snapshot = current.snapshot.copy(
                lastRenewedAtMs = nowMs,
                lastSeq = seq,
                phase = DisplayHoldPhase.HELD,
            )
            DisplayHoldTransition.Acquire(
                ownerId = current.snapshot.ownerId,
                seq = seq,
                ageMs = age(current, nowMs),
                leaseMs = remainingMs,
                resumed = resumed,
            )
        }.getOrElse {
            refused(current, seq, nowMs, DisplayHoldFailure.ACQUIRE_FAILED)
        }
    }

    private fun renewHeld(seq: Long, nowMs: Long): DisplayHoldTransition {
        val current = requireNotNull(episode)
        val lease = current.lease
            ?: return refused(current, seq, nowMs, DisplayHoldFailure.ACQUIRE_FAILED)
        val remainingMs = remaining(current, nowMs)
        return runCatching {
            // A non-reference-counted timed acquire replaces the lease timeout.
            // It is always the remaining original episode budget, never a fresh
            // ceiling, so updates cannot keep the panel on indefinitely.
            lease.acquire(remainingMs)
            current.snapshot = current.snapshot.copy(
                lastRenewedAtMs = nowMs,
                lastSeq = seq,
            )
            DisplayHoldTransition.Renew(
                ownerId = current.snapshot.ownerId,
                seq = seq,
                ageMs = age(current, nowMs),
                leaseMs = remainingMs,
            )
        }.getOrElse {
            refused(current, seq, nowMs, DisplayHoldFailure.ACQUIRE_FAILED)
        }
    }

    private fun refused(
        current: Episode,
        seq: Long,
        nowMs: Long,
        reason: DisplayHoldFailure,
    ): DisplayHoldTransition.Refused {
        current.snapshot = current.snapshot.copy(lastSeq = seq)
        return DisplayHoldTransition.Refused(
            ownerId = current.snapshot.ownerId,
            seq = seq,
            ageMs = age(current, nowMs),
            reason = reason,
        )
    }

    private fun remaining(current: Episode, nowMs: Long): Long =
        (ceilingMs - age(current, nowMs)).coerceAtLeast(1L)

    private fun age(current: Episode, nowMs: Long): Long =
        (nowMs - current.snapshot.startedAtMs).coerceAtLeast(0L)

    private fun releaseLease(current: Episode) {
        runCatching { current.lease?.release() }
    }
}

/**
 * The single owner of display wake decisions, budget state, and lock acquisition.
 *
 * [decide] is the complete pure one-shot policy. [requestWake] is its Android
 * edge. Assistant episodes use the separate renewable display-hold API below:
 * its lease bypasses the one-shot wake budget, ends explicitly, and can never
 * extend beyond its original safety ceiling.
 */
internal object DisplayWakePolicy {
    const val BUDGET_WINDOW_MS = 5_000L
    const val WAKE_LOCK_MS = 3_000L
    const val DISPLAY_HOLD_CEILING_MS = 90_000L
    const val MAX_UNATTENDED_NOTICE_WAKE_EPISODES = 2
    const val NOTICE_EPISODE_RESET_MS = 60_000L

    private var currentBudget = DisplayWakeBudget()
    private val displayHold = DisplayHoldLifecycle(DISPLAY_HOLD_CEILING_MS)
    private var displayHoldCeilingHandler: Handler? = null
    private var displayHoldCeilingTask: Runnable? = null

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

    @Synchronized
    @Suppress("DEPRECATION")
    fun beginDisplayHold(
        context: Context?,
        ownerId: String,
        seq: Long,
        requested: Boolean,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        displayHold.begin(
            ownerId = ownerId,
            seq = seq,
            requested = requested,
            nowMs = nowMs,
            leaseFactory = { newDisplayHoldLease(context) },
        ).forEach(::logHoldTransition)
        rescheduleDisplayHoldCeiling(nowMs)
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun renewDisplayHold(
        context: Context?,
        ownerId: String,
        seq: Long,
        eligibleToStart: Boolean,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        displayHold.renew(
            ownerId = ownerId,
            seq = seq,
            eligibleToStart = eligibleToStart,
            nowMs = nowMs,
            leaseFactory = { newDisplayHoldLease(context) },
        )?.let(::logHoldTransition)
        rescheduleDisplayHoldCeiling(nowMs)
    }

    @Synchronized
    fun suspendDisplayHold(reason: DisplayHoldReleaseReason) {
        val nowMs = SystemClock.elapsedRealtime()
        displayHold.suspend(reason, nowMs)?.let(::logHoldTransition)
        rescheduleDisplayHoldCeiling(nowMs)
    }

    @Synchronized
    fun releaseDisplayHold(reason: DisplayHoldReleaseReason) {
        val nowMs = SystemClock.elapsedRealtime()
        displayHold.end(reason, nowMs)?.let(::logHoldTransition)
        rescheduleDisplayHoldCeiling(nowMs)
    }

    @Suppress("DEPRECATION")
    private fun newDisplayHoldLease(context: Context?): DisplayHoldLease? {
        val power = context?.getSystemService(PowerManager::class.java) ?: return null
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "rokidbus:display-hold",
        ).apply {
            setReferenceCounted(false)
        }
        return object : DisplayHoldLease {
            override fun acquire(timeoutMs: Long) {
                wakeLock.acquire(timeoutMs)
            }

            override fun release() {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun rescheduleDisplayHoldCeiling(nowMs: Long) {
        val handler = displayHoldCeilingHandler ?: Handler(Looper.getMainLooper()).also {
            displayHoldCeilingHandler = it
        }
        displayHoldCeilingTask?.let(handler::removeCallbacks)
        displayHoldCeilingTask = null
        val snapshot = displayHold.snapshot()
            ?.takeUnless { it.phase == DisplayHoldPhase.CEILING }
            ?: return
        val task = Runnable {
            enforceDisplayHoldCeiling(snapshot.ownerId, snapshot.startedAtMs)
        }
        displayHoldCeilingTask = task
        handler.postDelayed(
            task,
            (DISPLAY_HOLD_CEILING_MS - (nowMs - snapshot.startedAtMs)).coerceAtLeast(0L),
        )
    }

    @Synchronized
    private fun enforceDisplayHoldCeiling(ownerId: String, startedAtMs: Long) {
        displayHoldCeilingTask = null
        displayHold.enforceCeiling(
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            nowMs = SystemClock.elapsedRealtime(),
        )?.let(::logHoldTransition)
    }

    private fun logHoldTransition(transition: DisplayHoldTransition) {
        val detail = when (transition) {
            is DisplayHoldTransition.Acquire ->
                "decision=acquire reason=${if (transition.resumed) "resume" else "engaged"} " +
                    "leaseMs=${transition.leaseMs}"
            is DisplayHoldTransition.Renew ->
                "decision=renew reason=active leaseMs=${transition.leaseMs}"
            is DisplayHoldTransition.Release ->
                "decision=release reason=${transition.reason.logValue} " +
                    "held=${transition.lockWasHeld}"
            is DisplayHoldTransition.Ceiling ->
                "decision=ceiling reason=safety_ceiling held=${transition.lockWasHeld}"
            is DisplayHoldTransition.Refused ->
                "decision=refused reason=${transition.reason.logValue}"
        }
        log(
            "hold seq=${transition.seq} $detail ageMs=${transition.ageMs} " +
                "owner=${transition.ownerId}",
        )
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
