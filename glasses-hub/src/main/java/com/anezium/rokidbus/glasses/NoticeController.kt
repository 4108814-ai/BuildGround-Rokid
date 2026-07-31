package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContent
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfacePatchResult
import com.anezium.rokidbus.shared.NoticeSurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class NexusNoticeSurface(
    val surfaceId: String,
    val seq: Long,
    val content: NoticeSurfaceContent,
    val expiresAtMs: Long,
    val hardExpiresAtMs: Long,
    val selectedActionIndex: Int = 0,
    val pageCount: Int = 1,
    val pageIndex: Int = 0,
    val engaged: Boolean = false,
    val imageBitmap: Bitmap? = null,
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

    val isPaged: Boolean get() = !content.expectsInput && pageCount > 1

    val claimsDirection: Boolean get() = liveActions.isNotEmpty() || isPaged
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

internal data class NoticePageWindow(
    val firstLine: Int,
    val lastLineExclusive: Int,
)

internal fun noticePageCount(
    lineCount: Int,
    firstPageLines: Int,
    followingPageLines: Int,
): Int {
    require(firstPageLines > 0)
    require(followingPageLines > 0)
    if (lineCount <= firstPageLines) return 1
    return 1 + (lineCount - firstPageLines + followingPageLines - 1) / followingPageLines
}

internal fun noticePageWindow(
    pageIndex: Int,
    lineCount: Int,
    firstPageLines: Int,
    followingPageLines: Int,
): NoticePageWindow {
    val count = noticePageCount(lineCount, firstPageLines, followingPageLines)
    val page = pageIndex.coerceIn(0, count - 1)
    val first = if (page == 0) {
        0
    } else {
        firstPageLines + (page - 1) * followingPageLines
    }
    val capacity = if (page == 0) firstPageLines else followingPageLines
    return NoticePageWindow(
        firstLine = first.coerceAtMost(lineCount),
        lastLineExclusive = (first + capacity).coerceAtMost(lineCount),
    )
}

/**
 * The exact text handed to the real [android.text.StaticLayout]. A body is
 * returned untouched for pixel compatibility; structured lines use the one
 * platform-owned hard break between entries and otherwise rely on that same
 * layout for wrapping and measurement.
 */
internal fun noticeBodyText(content: NoticeSurfaceContent): String? =
    if (content.lines.isEmpty()) content.body else content.lines.joinToString("\n")

/**
 * What a band's one answer turned out to be.
 *
 * Both kinds are the same event -- the wearer answered -- and differ only in
 * what goes on the wire, so they are one type. A band that offers a row is
 * answered by which choice was picked; a band that offers none is answered by
 * the fact that it was confirmed at all.
 */
internal sealed interface NoticeAnswer {
    data class Action(val action: NoticeAction) : NoticeAnswer
    data class Input(val keyCode: Int) : NoticeAnswer
}

internal sealed interface NoticeStateDecision {
    data class Shown(val notice: NexusNoticeSurface) : NoticeStateDecision
    data class Updated(val notice: NexusNoticeSurface) : NoticeStateDecision

    /**
     * The one answer this band had to give, taken. Carries everything the send
     * needs so that the send and the flag forbidding a second one are a single
     * transition, which two taps 188 ms apart cannot get between.
     */
    data class Answered(
        val notice: NexusNoticeSurface,
        val answer: NoticeAnswer,
    ) : NoticeStateDecision

    data class Closed(
        val surfaceId: String,
        val reason: NoticeCloseReason,
        val imageBitmap: Bitmap? = null,
    ) : NoticeStateDecision
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
        imageBitmap: Bitmap? = null,
    ): NoticeStateDecision {
        if (seq <= latestSeq) return NoticeStateDecision.DroppedStale
        latestSeq = seq
        val notice = NexusNoticeSurface(
            surfaceId = surfaceId,
            seq = seq,
            content = content,
            expiresAtMs = minOf(
                nowMs + content.ttlMs,
                nowMs + NoticeSurfaceContract.MAX_LIFETIME_MS,
            ),
            hardExpiresAtMs = nowMs + NoticeSurfaceContract.MAX_LIFETIME_MS,
            imageBitmap = imageBitmap,
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
        if (
            patched.title.isNullOrEmpty() &&
            patched.body.isNullOrEmpty() &&
            patched.lines.isEmpty()
        ) {
            return NoticeStateDecision.Ignored
        }
        latestSeq = seq
        val remainsEngaged = current.engaged && !patched.expectsInput
        val notice = current.copy(
            seq = seq,
            content = patched,
            // Updates restart ordinary notices, but reading has its own clock:
            // text arriving while the wearer is between pages is not a gesture
            // and cannot silently buy another thirty seconds.
            expiresAtMs = if (remainsEngaged) {
                current.expiresAtMs
            } else {
                minOf(nowMs + patched.ttlMs, current.hardExpiresAtMs)
            },
            selectedActionIndex = preservedNoticeActionIndex(
                previous = current.content.actions,
                previousIndex = current.selectedActionIndex,
                next = patched.actions,
            ),
            // An update that carries either field granting the band its
            // interactivity -- the row, or the plain interactive flag -- is the
            // owner asking again, so it is owed a new answer. An update that
            // carries neither is the owner driving an already-answered band as
            // a display, and must not quietly reopen it. Emptying the row or
            // clearing the flag resets too: there is then nothing left to
            // answer, and a flag left set would only be inherited by whatever
            // the owner asks next.
            answered = if (patch.actions != null || patch.interactive != null) {
                false
            } else {
                current.answered
            },
            pageCount = if (!patched.expectsInput) current.pageCount else 1,
            pageIndex = if (!patched.expectsInput) current.pageIndex else 0,
            engaged = remainsEngaged,
        )
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /**
     * Takes the band's one answer, whichever kind it has to give.
     *
     * Marking and reading happen in the same call on purpose: the duplicate tap
     * that started this arrived 188 ms after the first, and any gap between
     * "what is this band's answer" and "this band is now answered" is a gap two
     * taps can both fit through. That is why the plain input case comes through
     * here too rather than being checked and then forwarded.
     */
    fun answer(confirmKeyCode: Int): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (!current.expectsInput) return NoticeStateDecision.Ignored
        val answer = when (
            val action = current.content.actions.getOrNull(current.selectedActionIndex)
        ) {
            null -> NoticeAnswer.Input(confirmKeyCode)
            else -> NoticeAnswer.Action(action)
        }
        val notice = current.copy(answered = true)
        active = notice
        return NoticeStateDecision.Answered(notice, answer)
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

    fun setPageCount(
        surfaceId: String,
        seq: Long,
        count: Int,
    ): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (current.surfaceId != surfaceId || current.seq != seq) {
            return NoticeStateDecision.Ignored
        }
        val nextCount = if (!current.content.expectsInput) count.coerceAtLeast(1) else 1
        val nextIndex = current.pageIndex.coerceIn(0, nextCount - 1)
        if (current.pageCount == nextCount && current.pageIndex == nextIndex) {
            return NoticeStateDecision.Ignored
        }
        val notice = current.copy(pageCount = nextCount, pageIndex = nextIndex)
        active = notice
        return NoticeStateDecision.Updated(notice)
    }

    /**
     * Page reading deliberately differs from action selection: the first real
     * turn kills both countdowns, then every reading gesture restarts one short
     * inactivity clock so pace, rather than message length, owns the deadline.
     */
    fun movePage(delta: Int, nowMs: Long): NoticeStateDecision {
        val current = active ?: return NoticeStateDecision.Ignored
        if (!current.isPaged) return NoticeStateDecision.Ignored
        val next = (current.pageIndex + delta).coerceIn(0, current.pageCount - 1)
        if (!current.engaged && next == current.pageIndex) {
            return NoticeStateDecision.Ignored
        }
        val notice = current.copy(
            pageIndex = next,
            engaged = true,
            expiresAtMs = nowMs + ENGAGED_INACTIVITY_MS,
        )
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
        return NoticeStateDecision.Closed(closing.surfaceId, reason, closing.imageBitmap)
    }

    /** BACK and TTL are local: they carry no sequence from the phone. */
    fun close(reason: NoticeCloseReason): NoticeStateDecision {
        val closing = active ?: return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(closing.surfaceId, reason, closing.imageBitmap)
    }

    fun expire(nowMs: Long, expectedSeq: Long): NoticeStateDecision {
        val notice = active ?: return NoticeStateDecision.Ignored
        if (notice.seq != expectedSeq || nowMs < notice.expiresAtMs) return NoticeStateDecision.Ignored
        active = null
        return NoticeStateDecision.Closed(
            notice.surfaceId,
            NoticeCloseReason.TIMEOUT,
            notice.imageBitmap,
        )
    }

    private companion object {
        const val ENGAGED_INACTIVITY_MS = 30_000L
    }
}

internal object NoticeController {
    private val main = Handler(Looper.getMainLooper())
    private val state = NoticeStateMachine()
    private val listeners = CopyOnWriteArrayList<(NexusNoticeSurface?) -> Unit>()
    private val imageDecodeCoordinator = ImageDecodeCoordinator<Bitmap>()
    private val imageDecodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusNoticeImageDecode").apply { isDaemon = true }
    }
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

    fun handleNoticeEnvelope(context: Context, envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.NOTICE_SHOW -> {
            runOnMain { show(context.applicationContext, envelope) }
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
        runOnMain {
            discardPendingImage()
            applyDecision(state.close(NoticeCloseReason.USER))
        }
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
     * Forward and backward belong to the band only when they can change its
     * state: they choose an offered answer or turn measured pages. A plain
     * one-page notice claims neither, so the surface underneath stays usable.
     */
    fun claimsDirection(): Boolean =
        visibleNotice()?.claimsDirection == true

    /**
     * The wearer confirmed. The owner hears about it once; nobody else does.
     *
     * A band with actions answers with the selected one on `/notice/action`; a
     * band without them answers on `/notice/input`. Either way that is the
     * band's one answer, and the state machine decides which it is and marks it
     * spent in the same step -- there is no reading here for a second tap to
     * race.
     *
     * An answered band of either kind claims nothing: the second of two fast
     * taps falls through to whatever is underneath.
     */
    fun handleConfirm(keyCode: Int): Boolean {
        if (visibleNotice()?.expectsInput != true) return false
        runOnMain { applyDecision(state.answer(keyCode)) }
        return true
    }

    /** Steps the selection. False when there is nothing to step through. */
    fun handleDirection(delta: Int): Boolean {
        if (!claimsDirection()) return false
        runOnMain {
            val decision = if (state.activeNotice()?.liveActions?.isNotEmpty() == true) {
                state.moveSelection(delta)
            } else {
                state.movePage(delta, SystemClock.elapsedRealtime())
            }
            applyDecision(decision)
        }
        return true
    }

    fun setPageCount(surfaceId: String, seq: Long, count: Int) {
        main.post { applyDecision(state.setPageCount(surfaceId, seq, count)) }
    }

    /**
     * Which ring keys the band takes. The tap belongs to a question; directions
     * belong to either its row or measured pages. A plain one-page banner takes
     * neither, so the surface behind it stays usable and the ring never freezes.
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

    private fun show(context: Context, envelope: BusEnvelope) {
        val validation = NoticeSurfaceContract.validateShow(envelope.payload, envelope.binary)
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
        val image = validation.content.image
        if (image != null) {
            val bytes = envelope.binary ?: return
            val metadata = SurfaceImageMetadata(
                version = ImageSurfaceContract.VERSION,
                contentKey = image.contentKey,
                mimeType = image.mimeType,
                pixelWidth = image.pixelWidth,
                pixelHeight = image.pixelHeight,
                sha256 = image.sha256,
                caption = "",
            )
            val key = ImageDecodeKey(surfaceId, seq, image.contentKey)
            imageDecodeCoordinator.begin(key)
            imageDecodeExecutor.execute {
                val decoded = ImageHudView.decodeRgb565(bytes, metadata)
                if (decoded == null) {
                    log("Notice image decode failed id=$surfaceId seq=$seq")
                    main.post { imageDecodeCoordinator.cancel(key) }
                    return@execute
                }
                main.post {
                    when (val completion = imageDecodeCoordinator.complete(key, decoded)) {
                        is ImageDecodeCompletion.Rejected -> completion.stale.recycleSafely()
                        is ImageDecodeCompletion.Accepted -> {
                            completion.replaced?.takeUnless { it === decoded }?.recycleSafely()
                            showValidated(
                                context = context,
                                surfaceId = surfaceId,
                                seq = seq,
                                content = validation.content,
                                imageBitmap = decoded,
                            )
                            imageDecodeCoordinator.invalidate(surfaceId)
                                ?.takeUnless { it === decoded }
                                ?.recycleSafely()
                        }
                    }
                }
            }
            return
        }
        imageDecodeCoordinator.invalidate()?.let { pending ->
            if (pending !== state.activeNotice()?.imageBitmap) pending.recycleSafely()
        }
        showValidated(context, surfaceId, seq, validation.content)
    }

    private fun showValidated(
        context: Context,
        surfaceId: String,
        seq: Long,
        content: NoticeSurfaceContent,
        imageBitmap: Bitmap? = null,
    ) {
        val previous = state.activeNotice()
        val decision = state.show(
            surfaceId,
            seq,
            content,
            SystemClock.elapsedRealtime(),
            imageBitmap,
        )
        // A different plugin taking the slot is a close for the one that had it,
        // and its owner is owed the reason.
        if (decision is NoticeStateDecision.Shown &&
            previous != null &&
            previous.surfaceId != surfaceId
        ) {
            reportClosed(previous.surfaceId, NoticeCloseReason.REPLACED)
        }
        applyDecision(decision)
        if (decision is NoticeStateDecision.Shown) {
            DisplayWakePolicy.requestWake(
                context,
                DisplayWakeKind.NOTICE,
                requested = decision.notice.content.wakeDisplay,
            )
            previous?.imageBitmap
                ?.takeUnless { it === decision.notice.imageBitmap }
                ?.recycleSafely()
        } else {
            imageBitmap?.recycleSafely()
        }
    }

    private fun update(envelope: BusEnvelope) {
        if (envelope.binary != null) {
            log("notice update rejected code=${NoticeSurfaceContract.ERROR_INVALID_NOTICE}")
            return
        }
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
        discardPendingImage()
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
                when (val answer = decision.answer) {
                    is NoticeAnswer.Action ->
                        forwardAction(decision.notice.surfaceId, answer.action.id)
                    is NoticeAnswer.Input ->
                        forwardInput(decision.notice.surfaceId, answer.keyCode)
                }
                notifyChanged()
            }
            is NoticeStateDecision.Closed -> {
                cancelExpiry()
                main.removeCallbacks(ringTapExpiry)
                ringInputPolicy.reset()
                reportClosed(decision.surfaceId, decision.reason)
                notifyChanged()
                decision.imageBitmap?.let { released ->
                    main.postDelayed({ released.recycleSafely() }, HudMotion.EXIT_MS + 1L)
                }
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

    private fun discardPendingImage() {
        imageDecodeCoordinator.invalidate()?.let { pending ->
            if (pending !== state.activeNotice()?.imageBitmap) pending.recycleSafely()
        }
    }

    private fun notifyChanged() {
        val visible = visibleNotice()
        listeners.forEach { listener -> runCatching { listener(visible) } }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

private fun Bitmap.recycleSafely() {
    if (!isRecycled) recycle()
}
