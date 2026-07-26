package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import java.util.concurrent.CopyOnWriteArrayList

internal data class NexusNoticeSurface(
    val surfaceId: String,
    val seq: Long,
    val content: NoticeSurfaceContent,
    val expiresAtMs: Long,
)

internal sealed interface NoticeStateDecision {
    data class Shown(val notice: NexusNoticeSurface) : NoticeStateDecision
    data class Updated(val notice: NexusNoticeSurface) : NoticeStateDecision
    data class Closed(val surfaceId: String, val reason: NoticeCloseReason) : NoticeStateDecision
    data object DroppedStale : NoticeStateDecision
    data object Ignored : NoticeStateDecision
}

/** Pure single-slot notice state: sequence guard, patching, and the TTL clock. */
internal class NoticeStateMachine {
    private var latestSeq = Long.MIN_VALUE
    private var active: NexusNoticeSurface? = null

    fun activeNotice(): NexusNoticeSurface? = active

    fun show(
        surfaceId: String,
        seq: Long,
        content: NoticeSurfaceContent,
        nowMs: Long,
    ): NoticeStateDecision {
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        latestSeq = seq
        val notice = NexusNoticeSurface(
            surfaceId = surfaceId,
            seq = seq,
            content = content,
            expiresAtMs = nowMs + content.ttlMs,
        )
        active = notice
        return NoticeStateDecision.Shown(notice)
    }

    /**
     * Applies a patch to the visible notice. Ignored rather than rejected when
     * nothing is visible or the sender does not own the slot: an update racing a
     * TTL that fired a frame earlier is ordinary, not an error.
     */
    fun update(
        surfaceId: String,
        seq: Long,
        patch: com.anezium.rokidbus.shared.NoticeSurfacePatch,
        nowMs: Long,
    ): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.surfaceId != surfaceId) return NoticeStateDecision.Ignored
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        val patched = patch.applyTo(current.content)
        // An update is allowed to clear any single field, but not to leave the
        // wearer looking at an empty box.
        if (patched.title.isNullOrEmpty() && patched.body.isNullOrEmpty()) {
            return NoticeStateDecision.Ignored
        }
        latestSeq = seq
        val notice = current.copy(
            seq = seq,
            content = patched,
            // Every accepted update restarts the clock; that is what lets a
            // transcript keep a banner alive while it is being dictated.
            expiresAtMs = nowMs + patched.ttlMs,
        )
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    fun hide(seq: Long, reason: NoticeCloseReason): NoticeStateDecision {
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        latestSeq = seq
        val closing = active ?: return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(closing.surfaceId, reason)
    }

    /** BACK and TTL are local: they carry no sequence from the phone. */
    fun close(reason: NoticeCloseReason): NoticeStateDecision {
        val closing = active ?: return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(closing.surfaceId, reason)
    }

    fun expire(nowMs: Long, expectedSeq: Long): NoticeStateDecision {
        val notice = active ?: return NoticeStateDecision.Ignored
        if (notice.seq != expectedSeq || nowMs < notice.expiresAtMs) return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(notice.surfaceId, NoticeCloseReason.TIMEOUT)
    }
}

internal object NoticeController {
    private val main = Handler(Looper.getMainLooper())
    private val state = NoticeStateMachine()
    private val listeners = CopyOnWriteArrayList<(NexusNoticeSurface?) -> Unit>()
    private var expiry: Runnable? = null
    private var cameraOverlayActive = false

    fun activeNotice(): NexusNoticeSurface? = state.activeNotice()

    fun visibleNotice(): NexusNoticeSurface? =
        state.activeNotice().takeUnless { cameraOverlayActive }

    fun observe(listener: (NexusNoticeSurface?) -> Unit): () -> Unit {
        listeners += listener
        listener(visibleNotice())
        return { listeners.remove(listener) }
    }

    fun handleNoticeEnvelope(envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.NOTICE_SHOW -> {
            runOnMain { show(envelope) }
            true
        }
        BusPaths.NOTICE_UPDATE -> {
            runOnMain { update(envelope) }
            true
        }
        BusPaths.NOTICE_HIDE -> {
            runOnMain { hide(envelope) }
            true
        }
        else -> false
    }

    /**
     * BACK dismisses whatever notice is up, always, and is never forwarded to the
     * plugin that put it there. A notice a plugin could hold you inside would be
     * a different and much worse thing.
     *
     * Returns true when a notice was actually dismissed, so the caller knows
     * whether it consumed the key.
     */
    fun dismissFromBack(): Boolean {
        if (visibleNotice() == null) return false
        runOnMain { applyDecision(state.close(NoticeCloseReason.USER)) }
        return true
    }

    fun setCameraOverlayActive(active: Boolean) {
        runOnMain {
            if (cameraOverlayActive == active) return@runOnMain
            cameraOverlayActive = active
            notifyChanged()
        }
    }

    private fun show(envelope: BusEnvelope) {
        val validation = NoticeSurfaceContract.validateShow(envelope.payload)
        if (validation !is NoticeSurfaceValidationResult.Valid) {
            log("notice rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        if (surfaceId.isBlank()) {
            log("notice rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        val previous = state.activeNotice()
        val decision = state.show(surfaceId, seq, validation.content, SystemClock.elapsedRealtime())
        // A different plugin taking the slot is a close for the one that had it,
        // and its owner is owed the reason.
        if (decision is NoticeStateDecision.Shown &&
            previous != null &&
            previous.surfaceId != surfaceId
        ) {
            reportClosed(previous.surfaceId, NoticeCloseReason.REPLACED)
        }
        applyDecision(decision)
    }

    private fun update(envelope: BusEnvelope) {
        val patch = NoticeSurfaceContract.validateUpdate(envelope.payload)
        if (patch !is NoticeSurfacePatchResult.Valid) {
            log("notice update rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
        val surfaceId = envelope.payload.optString("surfaceId")
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        applyDecision(
            state.update(surfaceId, seq, patch.patch, SystemClock.elapsedRealtime()),
        )
    }

    private fun hide(envelope: BusEnvelope) {
        val seq = envelope.payload.optLong("seq", Long.MIN_VALUE)
        applyDecision(state.hide(seq, NoticeCloseReason.OWNER))
    }

    private fun applyDecision(decision: NoticeStateDecision) {
        when (decision) {
            is NoticeStateDecision.Shown -> {
                scheduleExpiry(decision.notice)
                notifyChanged()
            }
            is NoticeStateDecision.Updated -> {
                scheduleExpiry(decision.notice)
                notifyChanged()
            }
            is NoticeStateDecision.Closed -> {
                cancelExpiry()
                reportClosed(decision.surfaceId, decision.reason)
                notifyChanged()
            }
            NoticeStateDecision.DroppedStale -> log("notice dropped stale")
            NoticeStateDecision.Ignored -> Unit
        }
    }

    /** Tells the phone the slot is free, and why, so it can tell the owner. */
    private fun reportClosed(surfaceId: String, reason: NoticeCloseReason) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_CLOSED,
            NoticeSurfaceContract.closedPayload(surfaceId, reason),
        )
    }

    private fun scheduleExpiry(notice: NexusNoticeSurface) {
        cancelExpiry()
        val task = Runnable {
            expiry = null
            applyDecision(state.expire(SystemClock.elapsedRealtime(), notice.seq))
        }
        expiry = task
        main.postDelayed(
            task,
            (notice.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        )
    }

    private fun cancelExpiry() {
        expiry?.let(main::removeCallbacks)
        expiry = null
    }

    private fun notifyChanged() {
        val visible = visibleNotice()
        listeners.forEach { listener -> runCatching { listener(visible) } }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
