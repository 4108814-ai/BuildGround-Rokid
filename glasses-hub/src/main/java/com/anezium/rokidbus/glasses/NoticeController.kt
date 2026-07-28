package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal data class NexusNoticeSurface(
    val surfaceId: String,
    val seq: Long,
    val content: NoticeSurfaceContent,
    val expiresAtMs: Long,
    val selectedActionIndex: Int = 0,
    /**
     * The wearer has picked. A notice takes exactly one answer: measured on
     * device, two temple taps 188 ms apart fired the action twice, and for a
     * messaging plugin that is two replies sent.
     */
    val answered: Boolean = false,
) {
    /**
     * The actions still on offer. An answered band shows none: the question has
     * been answered, so the choices have no reason to stay in the wearer's eye.
     */
    val liveActions: List<NoticeAction>
        get() = if (answered) emptyList() else content.actions

    /**
     * Whether the band still wants a gesture. An answered one never does again
     * -- not another action, and not the plain confirming input either.
     */
    val expectsInput: Boolean get() = !answered && content.expectsInput
}

/**
 * Where the selection lands after a step. Wraps in both directions: the row is
 * three glyphs at most, so a dead end at either edge would only be a way to
 * make the wearer press again for nothing.
 */
internal fun nextNoticeActionIndex(current: Int, delta: Int, count: Int): Int =
    if (count <= 0) 0 else ((current + delta) % count + count) % count

/**
 * The selection to keep when an update replaces the row.
 *
 * Follows the id, not the position: a plugin that reorders its answers, or
 * drops the one before the selected one, must not move the wearer's finger onto
 * a different answer than the one they were looking at. When the selected id is
 * gone the selection falls back to the first action, which is the only choice
 * that cannot be a surprise.
 */
internal fun preservedNoticeActionIndex(
    previous: List<NoticeAction>,
    previousIndex: Int,
    next: List<NoticeAction>,
): Int {
    if (next.isEmpty()) return 0
    val selectedId = previous.getOrNull(previousIndex)?.id ?: return 0
    return next.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
}

internal sealed interface NoticeStateDecision {
    data class Shown(val notice: NexusNoticeSurface) : NoticeStateDecision
    data class Updated(val notice: NexusNoticeSurface) : NoticeStateDecision

    /**
     * The one answer this band had to give, taken. Carries the action so the
     * send and the flag that forbids a second one are a single transition and
     * cannot be separated by a second tap.
     */
    data class Answered(
        val notice: NexusNoticeSurface,
        val action: NoticeAction,
    ) : NoticeStateDecision

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
            selectedActionIndex = preservedNoticeActionIndex(
                previous = current.content.actions,
                previousIndex = current.selectedActionIndex,
                next = patched.actions,
            ),
            // An update that carries the actions field is a new question, so it
            // is owed a new answer. One that leaves the field out is the owner
            // driving an already-answered band as a display, and must not
            // quietly reopen it. An empty array resets too: there is nothing
            // left to answer, and leaving the flag set would only mean a later
            // row inherited a stale one.
            answered = if (patch.actions != null) false else current.answered,
        )
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /**
     * Takes the band's one answer, if it still has one to give.
     *
     * Marking and reading happen in the same call on purpose: the duplicate tap
     * that started this arrived 188 ms after the first, and any gap between
     * "which action is selected" and "this band is now answered" is a gap two
     * taps can both fit through.
     */
    fun answer(): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.answered) return NoticeStateDecision.Ignored
        val action = current.content.actions.getOrNull(current.selectedActionIndex)
            ?: return NoticeStateDecision.Ignored
        val notice = current.copy(answered = true)
        active = notice
        return NoticeStateDecision.Answered(notice, action)
    }

    /**
     * Steps the selection along the action row.
     *
     * Deliberately does not touch `expiresAtMs`: choosing is not a reason for
     * the band to live longer. A notice with actions dies on exactly the
     * deadline it would have died on with none, which is what keeps a question
     * from becoming a thing the wearer has to escape.
     */
    fun moveSelection(delta: Int): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        val count = current.liveActions.size
        if (count == 0) return NoticeStateDecision.Ignored
        val next = nextNoticeActionIndex(current.selectedActionIndex, delta, count)
        if (next == current.selectedActionIndex) return NoticeStateDecision.Ignored
        val notice = current.copy(selectedActionIndex = next)
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /** The action the wearer would fire right now, if the band still offers any. */
    fun selectedAction(): NoticeAction? =
        active?.let { it.liveActions.getOrNull(it.selectedActionIndex) }

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
    private val ringInputPolicy = RingSurfaceInputPolicy()
    private val ringTapExpiry = Runnable(::resolveRingTap)

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

    /**
     * Whether a notice is up and asked for a gesture. Only then does anything
     * below claim a key, and only the keys that mean confirm and dismiss:
     * everything else keeps reaching whatever is underneath, because a banner
     * is not a reason for the glasses to stop responding.
     */
    fun claimsInput(): Boolean = visibleNotice()?.expectsInput == true

    /**
     * Whether the band is offering a choice, and so has somewhere for forward
     * and backward to go. A notice without actions -- or one whose actions have
     * already been answered -- never claims a direction: scroll keeps reaching
     * whatever is underneath it, exactly as before.
     */
    fun claimsDirection(): Boolean =
        visibleNotice()?.liveActions?.isNotEmpty() == true

    /**
     * The wearer confirmed. The owner hears about it; nobody else does.
     *
     * A band with actions answers with the one that is selected, on
     * `/notice/action`, and does so exactly once. A band without them keeps the
     * single-gesture reply on `/notice/input` it has always had, unchanged.
     *
     * An answered band claims nothing: the second of two fast taps falls
     * through to whatever is underneath, and in particular does not fall back
     * to firing input just because the row is gone.
     */
    fun handleConfirm(keyCode: Int): Boolean {
        val notice = visibleNotice()?.takeIf { it.expectsInput } ?: return false
        if (notice.content.actions.isEmpty()) {
            forwardInput(notice.surfaceId, keyCode)
            return true
        }
        runOnMain { applyDecision(state.answer()) }
        return true
    }

    /** Steps the selection. False when there is nothing to step through. */
    fun handleDirection(delta: Int): Boolean {
        if (!claimsDirection()) return false
        runOnMain { applyDecision(state.moveSelection(delta)) }
        return true
    }

    /**
     * Which ring keys the band takes. The tap whenever the notice expects an
     * answer at all; scroll only while there is a row to move along, so a
     * surface behind a plain banner stays usable and the ring never freezes.
     */
    fun claimsRingKey(keyCode: Int): Boolean = when (keyCode) {
        RingSurfaceInputPolicy.RING_KEYCODE_TAP -> claimsInput()
        RingSurfaceInputPolicy.RING_KEYCODE_FORWARD,
        RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD,
        -> claimsDirection()
        else -> false
    }

    fun handleRingKey(keyCode: Int, eventTimeMs: Long): Boolean {
        if (!claimsRingKey(keyCode)) return false
        return when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD -> handleDirection(1)
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD -> handleDirection(-1)
            else -> {
                ringInputPolicy.onKeyDown(keyCode, eventTimeMs)
                main.removeCallbacks(ringTapExpiry)
                main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
                true
            }
        }
    }

    fun cancelRingInput() {
        runOnMain {
            main.removeCallbacks(ringTapExpiry)
            ringInputPolicy.reset()
        }
    }

    private fun resolveRingTap() {
        when (ringInputPolicy.resolveExpired(SystemClock.elapsedRealtime())) {
            is RingSurfaceInputPolicy.Resolution.Forward ->
                handleConfirm(RingSurfaceInputPolicy.KEYCODE_ENTER)
            // A double tap on the ring is the wearer's dismiss, same as BACK.
            RingSurfaceInputPolicy.Resolution.Back -> dismissFromBack()
            RingSurfaceInputPolicy.Resolution.Ignore, null -> Unit
        }
    }

    private fun forwardAction(surfaceId: String, actionId: String) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_ACTION,
            NoticeSurfaceContract.actionPayload(surfaceId, actionId),
        )
    }

    private fun forwardInput(surfaceId: String, keyCode: Int) {
        GlassesHub.sendToPhone(
            BusPaths.NOTICE_INPUT,
            JSONObject()
                .put("noticeId", surfaceId)
                .put("keyCode", keyCode)
                .put("action", KeyEvent.ACTION_DOWN),
        )
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
            is NoticeStateDecision.Answered -> {
                // No expiry rescheduling: answering neither shortens nor extends
                // the band's life, and the deadline it was already given still
                // stands. The re-render is what makes the row leave the band.
                forwardAction(decision.notice.surfaceId, decision.action.id)
                notifyChanged()
            }
            is NoticeStateDecision.Closed -> {
                cancelExpiry()
                main.removeCallbacks(ringTapExpiry)
                ringInputPolicy.reset()
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
