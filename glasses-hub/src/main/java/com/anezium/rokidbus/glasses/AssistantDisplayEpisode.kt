package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContract

internal object NoticeDisplayHoldPolicy {
    const val ASSISTANT_PLUGIN_ID = "assistant"
    private const val ASSISTANT_NOTICE_ID =
        "$ASSISTANT_PLUGIN_ID:${NoticeSurfaceContract.LOCAL_SURFACE_ID}"

    fun noticeHoldsDisplay(surfaceId: String, engaged: Boolean): Boolean =
        surfaceId == ASSISTANT_NOTICE_ID && engaged

    fun assistantOwnsNotice(surfaceId: String): Boolean = surfaceId == ASSISTANT_NOTICE_ID

    fun assistantOwnsSurface(ownerPluginId: String): Boolean =
        ownerPluginId == ASSISTANT_PLUGIN_ID

    fun differentPluginEngagedNotice(
        ownerPluginId: String,
        engaged: Boolean,
    ): Boolean = engaged && ownerPluginId.isNotBlank() && ownerPluginId != ASSISTANT_PLUGIN_ID
}

internal enum class DisplayHoldReleaseReason(val logValue: String) {
    WEARER_DISMISSED("wearer_dismissed"),
    SESSION_CLOSED("session_closed"),
    NON_ASSISTANT_SURFACE("non_assistant_surface"),
    ENGAGED_NOTICE_TAKEOVER("engaged_notice_takeover"),
    LINK_LOSS("link_loss"),
    SERVICE_DESTROYED("service_destroyed"),
    RENDERER_ERROR("renderer_error"),
    SAFETY_CEILING("safety_ceiling"),
    HOLD_FAILURE("hold_failure"),
}

internal enum class DisplayHoldRenewReason(val logValue: String) {
    FOLLOW_UP("follow_up"),
    ANSWER_SHOWN("answer_shown"),
    BAND_UPDATE("band_update"),
    BAND_ANSWER("band_answer"),
    ;

    /**
     * A question and the answer it produces are the two moments the wearer has
     * something new to look at, and only those re-arm the deadline. Band
     * updates and card redraws re-assert what is left, so nothing the hub draws
     * to itself can extend the hold.
     */
    val rearmsDeadline: Boolean
        get() = this == FOLLOW_UP || this == ANSWER_SHOWN
}

internal data class DisplayHoldSnapshot(
    val episodeId: Long,
    val ownerId: String,
    val startedAtMs: Long,
    val deadlineAtMs: Long,
    val lastRenewedAtMs: Long,
    val lastSeq: Long,
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
    ) : DisplayHoldTransition

    data class Renew(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val leaseMs: Long,
        val reason: DisplayHoldRenewReason,
    ) : DisplayHoldTransition

    data class Release(
        override val ownerId: String,
        override val seq: Long,
        override val ageMs: Long,
        val reason: DisplayHoldReleaseReason,
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

internal sealed interface AssistantEpisodeSignal {
    data class Engage(val seq: Long) : AssistantEpisodeSignal
    data class Renew(
        val seq: Long,
        val reason: DisplayHoldRenewReason,
    ) : AssistantEpisodeSignal
    data class End(val reason: DisplayHoldReleaseReason) : AssistantEpisodeSignal
    data object None : AssistantEpisodeSignal
}

internal fun assistantEpisodeNoticeShownSignal(
    surfaceId: String,
    ownerPluginId: String,
    seq: Long,
    engaged: Boolean,
): AssistantEpisodeSignal = when {
    NoticeDisplayHoldPolicy.noticeHoldsDisplay(surfaceId, engaged) &&
        ownerPluginId == NoticeDisplayHoldPolicy.ASSISTANT_PLUGIN_ID ->
        AssistantEpisodeSignal.Engage(seq)
    NoticeDisplayHoldPolicy.differentPluginEngagedNotice(ownerPluginId, engaged) ->
        AssistantEpisodeSignal.End(DisplayHoldReleaseReason.ENGAGED_NOTICE_TAKEOVER)
    else -> AssistantEpisodeSignal.None
}

internal fun assistantEpisodeNoticeRedrawSignal(
    surfaceId: String,
    seq: Long,
    engaged: Boolean,
    reason: DisplayHoldRenewReason,
): AssistantEpisodeSignal = if (
    NoticeDisplayHoldPolicy.noticeHoldsDisplay(surfaceId, engaged)
) {
    AssistantEpisodeSignal.Renew(seq, reason)
} else {
    AssistantEpisodeSignal.None
}

internal fun assistantEpisodeNoticeClosedSignal(
    surfaceId: String,
    reason: NoticeCloseReason,
    preserveOwnerClose: Boolean,
): AssistantEpisodeSignal {
    if (!NoticeDisplayHoldPolicy.assistantOwnsNotice(surfaceId)) {
        return AssistantEpisodeSignal.None
    }
    return when (reason) {
        NoticeCloseReason.USER ->
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.WEARER_DISMISSED)
        NoticeCloseReason.OWNER -> if (preserveOwnerClose) {
            AssistantEpisodeSignal.None
        } else {
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.SESSION_CLOSED)
        }
        NoticeCloseReason.DISCONNECT ->
            AssistantEpisodeSignal.End(DisplayHoldReleaseReason.LINK_LOSS)
        NoticeCloseReason.TIMEOUT,
        NoticeCloseReason.REPLACED,
        -> AssistantEpisodeSignal.None
    }
}

/**
 * The answer becoming visible. An Ink card can arrive a long way into an
 * episode, so it re-arms the deadline: the wearer gets the full window to read
 * what they asked for, counted from the moment it appears rather than from the
 * question. Fires once per show — later patches to the same card do not.
 */
internal fun assistantEpisodeAnswerShownSignal(
    ownerPluginId: String,
    seq: Long,
): AssistantEpisodeSignal = if (NoticeDisplayHoldPolicy.assistantOwnsSurface(ownerPluginId)) {
    AssistantEpisodeSignal.Renew(seq, DisplayHoldRenewReason.ANSWER_SHOWN)
} else {
    AssistantEpisodeSignal.None
}

internal fun assistantEpisodeSurfacePresentedSignal(ownerPluginId: String): AssistantEpisodeSignal =
    if (NoticeDisplayHoldPolicy.assistantOwnsSurface(ownerPluginId)) {
        AssistantEpisodeSignal.None
    } else {
        AssistantEpisodeSignal.End(DisplayHoldReleaseReason.NON_ASSISTANT_SURFACE)
    }

internal fun assistantEpisodeSurfaceEndedSignal(
    ownerPluginId: String,
    reason: DisplayHoldReleaseReason,
): AssistantEpisodeSignal = if (NoticeDisplayHoldPolicy.assistantOwnsSurface(ownerPluginId)) {
    AssistantEpisodeSignal.End(reason)
} else {
    AssistantEpisodeSignal.None
}

/**
 * The one logical display owner for an engaged Assistant conversation.
 *
 * A fresh engaged band is genuine wearer engagement. It re-arms the deadline
 * without replacing the episode or its non-reference-counted lease. Band
 * updates may re-assert only the time still left. Morphs, Ink frames and card
 * redraws produce no signal at all. Consequently, unattended rendering can
 * hold the panel for at most [ceilingMs] after the last genuine question.
 */
internal class AssistantEpisodeHoldLifecycle(
    private val ceilingMs: Long,
    private val ownerId: String = AssistantDisplayEpisode.OWNER_ID,
) {
    private data class Episode(
        var snapshot: DisplayHoldSnapshot,
        val lease: DisplayHoldLease,
    )

    private var nextEpisodeId = 0L
    private var episode: Episode? = null

    fun snapshot(): DisplayHoldSnapshot? = episode?.snapshot

    fun apply(
        signal: AssistantEpisodeSignal,
        nowMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): List<DisplayHoldTransition> = when (signal) {
        is AssistantEpisodeSignal.Engage -> engage(signal.seq, nowMs, leaseFactory)
        is AssistantEpisodeSignal.Renew ->
            renew(signal.seq, signal.reason, nowMs)?.let(::listOf).orEmpty()
        is AssistantEpisodeSignal.End -> end(signal.reason, nowMs)?.let(::listOf).orEmpty()
        AssistantEpisodeSignal.None -> emptyList()
    }

    fun enforceCeiling(
        episodeId: Long,
        deadlineAtMs: Long,
        nowMs: Long,
    ): DisplayHoldTransition.Release? {
        val current = episode ?: return null
        if (
            current.snapshot.episodeId != episodeId ||
            current.snapshot.deadlineAtMs != deadlineAtMs ||
            nowMs < deadlineAtMs
        ) {
            return null
        }
        return end(DisplayHoldReleaseReason.SAFETY_CEILING, nowMs)
    }

    private fun engage(
        seq: Long,
        nowMs: Long,
        leaseFactory: () -> DisplayHoldLease?,
    ): List<DisplayHoldTransition> {
        val transitions = mutableListOf<DisplayHoldTransition>()
        episode?.takeIf { nowMs >= it.snapshot.deadlineAtMs }?.let { expired ->
            enforceCeiling(
                episodeId = expired.snapshot.episodeId,
                deadlineAtMs = expired.snapshot.deadlineAtMs,
                nowMs = nowMs,
            )?.let(transitions::add)
        }
        if (episode != null) {
            renew(seq, DisplayHoldRenewReason.FOLLOW_UP, nowMs)?.let(transitions::add)
            return transitions
        }

        val lease = runCatching(leaseFactory).getOrNull()
        if (lease == null) {
            transitions += refused(seq, DisplayHoldFailure.POWER_SERVICE_UNAVAILABLE)
            return transitions
        }
        val acquired = runCatching { lease.acquire(ceilingMs) }.isSuccess
        if (!acquired) {
            runCatching { lease.release() }
            transitions += refused(seq, DisplayHoldFailure.ACQUIRE_FAILED)
            return transitions
        }
        nextEpisodeId += 1L
        episode = Episode(
            snapshot = DisplayHoldSnapshot(
                episodeId = nextEpisodeId,
                ownerId = ownerId,
                startedAtMs = nowMs,
                deadlineAtMs = nowMs + ceilingMs,
                lastRenewedAtMs = nowMs,
                lastSeq = seq,
            ),
            lease = lease,
        )
        transitions += DisplayHoldTransition.Acquire(
            ownerId = ownerId,
            seq = seq,
            ageMs = 0L,
            leaseMs = ceilingMs,
        )
        return transitions
    }

    private fun renew(
        seq: Long,
        reason: DisplayHoldRenewReason,
        nowMs: Long,
    ): DisplayHoldTransition? {
        val current = episode ?: return null
        if (nowMs >= current.snapshot.deadlineAtMs) {
            return enforceCeiling(
                episodeId = current.snapshot.episodeId,
                deadlineAtMs = current.snapshot.deadlineAtMs,
                nowMs = nowMs,
            )
        }
        val rearmed = reason.rearmsDeadline
        val deadlineAtMs = if (rearmed) nowMs + ceilingMs else current.snapshot.deadlineAtMs
        val leaseMs = (deadlineAtMs - nowMs).coerceAtLeast(1L)
        if (runCatching { current.lease.acquire(leaseMs) }.isFailure) {
            return end(DisplayHoldReleaseReason.HOLD_FAILURE, nowMs)
        }
        current.snapshot = current.snapshot.copy(
            deadlineAtMs = deadlineAtMs,
            lastRenewedAtMs = nowMs,
            lastSeq = seq,
        )
        return DisplayHoldTransition.Renew(
            ownerId = ownerId,
            seq = seq,
            ageMs = age(current, nowMs),
            leaseMs = leaseMs,
            reason = reason,
        )
    }

    private fun end(
        reason: DisplayHoldReleaseReason,
        nowMs: Long,
    ): DisplayHoldTransition.Release? {
        val current = episode ?: return null
        // Clear first so duplicate or re-entrant terminal callbacks cannot
        // release the same episode twice even if the platform release throws.
        episode = null
        runCatching { current.lease.release() }
        return DisplayHoldTransition.Release(
            ownerId = ownerId,
            seq = current.snapshot.lastSeq,
            ageMs = age(current, nowMs),
            reason = reason,
            lockWasHeld = true,
        )
    }

    private fun refused(seq: Long, reason: DisplayHoldFailure) =
        DisplayHoldTransition.Refused(
            ownerId = ownerId,
            seq = seq,
            ageMs = 0L,
            reason = reason,
        )

    private fun age(current: Episode, nowMs: Long): Long =
        (nowMs - current.snapshot.startedAtMs).coerceAtLeast(0L)
}

internal object AssistantDisplayEpisode {
    const val OWNER_ID = "assistant:episode"
    const val DISPLAY_HOLD_CEILING_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = AssistantEpisodeHoldLifecycle(DISPLAY_HOLD_CEILING_MS)
    private var ceilingTask: Runnable? = null

    fun accept(context: Context?, signal: AssistantEpisodeSignal) {
        runOnMain { applySignal(context, signal) }
    }

    fun end(reason: DisplayHoldReleaseReason) {
        accept(null, AssistantEpisodeSignal.End(reason))
    }

    @Synchronized
    fun isActive(): Boolean = lifecycle.snapshot() != null

    @Synchronized
    @Suppress("DEPRECATION")
    private fun applySignal(context: Context?, signal: AssistantEpisodeSignal) {
        val nowMs = SystemClock.elapsedRealtime()
        lifecycle.apply(
            signal = signal,
            nowMs = nowMs,
            leaseFactory = { newLease(context) },
        ).forEach(::logTransition)
        rescheduleCeiling(nowMs)
    }

    @Suppress("DEPRECATION")
    private fun newLease(context: Context?): DisplayHoldLease? {
        val power = context?.getSystemService(PowerManager::class.java) ?: return null
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "rokidbus:assistant-episode",
        ).apply { setReferenceCounted(false) }
        return object : DisplayHoldLease {
            override fun acquire(timeoutMs: Long) {
                wakeLock.acquire(timeoutMs)
            }

            override fun release() {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun rescheduleCeiling(nowMs: Long) {
        ceilingTask?.let(main::removeCallbacks)
        ceilingTask = null
        val snapshot = lifecycle.snapshot() ?: return
        val task = Runnable {
            enforceCeiling(snapshot.episodeId, snapshot.deadlineAtMs)
        }
        ceilingTask = task
        main.postDelayed(task, (snapshot.deadlineAtMs - nowMs).coerceAtLeast(0L))
    }

    @Synchronized
    private fun enforceCeiling(episodeId: Long, deadlineAtMs: Long) {
        ceilingTask = null
        lifecycle.enforceCeiling(
            episodeId = episodeId,
            deadlineAtMs = deadlineAtMs,
            nowMs = SystemClock.elapsedRealtime(),
        )?.let(::logTransition)
    }

    private fun logTransition(transition: DisplayHoldTransition) {
        log(formatDisplayHoldTransition(transition))
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

internal fun formatDisplayHoldTransition(transition: DisplayHoldTransition): String {
    val detail = when (transition) {
        is DisplayHoldTransition.Acquire ->
            "decision=acquire reason=engaged leaseMs=${transition.leaseMs}"
        is DisplayHoldTransition.Renew ->
            "decision=renew reason=${transition.reason.logValue} leaseMs=${transition.leaseMs}"
        is DisplayHoldTransition.Release ->
            "decision=release reason=${transition.reason.logValue} held=${transition.lockWasHeld}"
        is DisplayHoldTransition.Refused ->
            "decision=refused reason=${transition.reason.logValue}"
    }
    return "hold seq=${transition.seq} $detail ageMs=${transition.ageMs} owner=${transition.ownerId}"
}
