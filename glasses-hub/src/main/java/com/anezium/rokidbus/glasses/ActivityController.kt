package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatch
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PinSurfacePosition
import java.util.concurrent.CopyOnWriteArrayList

internal data class NexusActivitySurface(
    val surfaceId: String,
    val ownerPluginId: String,
    val seq: Long,
    val content: ActivitySurfaceContent,
    val corner: PinSurfacePosition,
    val startedOrder: Long,
    val lastUpdatedOrder: Long,
    val lastSignificantOrder: Long?,
    val collapseAtMs: Long,
    val maxDurationDeadlineMs: Long?,
    val selectedActionIndex: Int,
    val motionToken: Long,
)

internal data class ActivityRenderItem(
    val activity: NexusActivitySurface,
    val primary: Boolean,
    val presentation: ActivityPresentation,
)

internal data class ActivityRenderState(
    val items: List<ActivityRenderItem> = emptyList(),
) {
    val primary: ActivityRenderItem?
        get() = items.firstOrNull(ActivityRenderItem::primary)
}

internal sealed interface ActivityMutation {
    data class Applied(
        val surfaceId: String,
        val significant: Boolean,
        val replacedSurfaceId: String? = null,
    ) : ActivityMutation

    data class Removed(val surfaceId: String) : ActivityMutation
    data class Cleared(val surfaceIds: List<String>) : ActivityMutation
    data object DroppedStale : ActivityMutation
    data object Ignored : ActivityMutation
}

/**
 * Pure multi-slot activity state.
 *
 * Presentation context is deliberately supplied by the caller. This class
 * owns activity facts and clocks; it never asks Android what is in front of
 * the wearer and it never accepts a plugin-selected presentation.
 */
internal class ActivityStateMachine {
    private data class Resident(
        val surfaceId: String,
        val ownerPluginId: String,
        val seq: Long,
        val content: ActivitySurfaceContent,
        val startedOrder: Long,
        val lastUpdatedOrder: Long,
        val lastSignificantOrder: Long?,
        val lastFlareAtMs: Long?,
        val collapseAtMs: Long,
        val maxDurationDeadlineMs: Long?,
        val selectedActionIndex: Int,
        val motionToken: Long,
    )

    private val residents = linkedMapOf<String, Resident>()
    private val latestSeqBySurface = mutableMapOf<String, Long>()
    private var globalClearSeq = Long.MIN_VALUE
    private var order = 0L
    private var motionToken = 0L
    private var corners = emptyMap<String, PinSurfacePosition>()

    fun surfaceIds(): Set<String> = residents.keys.toSet()

    fun primarySurfaceId(): String? = selectPrimaryActivity(
        residents.values.map {
            ActivityPrimaryCandidate(
                activityId = it.surfaceId,
                startedOrder = it.startedOrder,
                lastSignificantOrder = it.lastSignificantOrder,
            )
        },
    )

    fun start(
        surfaceId: String,
        ownerPluginId: String,
        seq: Long,
        content: ActivitySurfaceContent,
        nowMs: Long,
    ): ActivityMutation {
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq

        var replacedSurfaceId: String? = null
        if (surfaceId !in residents &&
            residents.size >= ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES
        ) {
            replacedSurfaceId = evictionCandidate()
            residents.remove(replacedSurfaceId)
            corners = corners - replacedSurfaceId
        }

        val previous = residents[surfaceId]
        val nextOrder = ++order
        val next = Resident(
            surfaceId = surfaceId,
            ownerPluginId = ownerPluginId,
            seq = seq,
            content = content,
            startedOrder = nextOrder,
            lastUpdatedOrder = nextOrder,
            lastSignificantOrder = null,
            lastFlareAtMs = null,
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            maxDurationDeadlineMs = content.maxDurationMs?.let { nowMs + it },
            selectedActionIndex = previous
                ?.selectedActionIndex
                ?.coerceIn(0, (content.actions.lastIndex).coerceAtLeast(0))
                ?: 0,
            motionToken = ++motionToken,
        )
        residents[surfaceId] = next
        return ActivityMutation.Applied(
            surfaceId = surfaceId,
            significant = false,
            replacedSurfaceId = replacedSurfaceId,
        )
    }

    fun update(
        surfaceId: String,
        seq: Long,
        patch: ActivitySurfacePatch,
        nowMs: Long,
    ): ActivityMutation {
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq
        val current = residents[surfaceId] ?: return ActivityMutation.Ignored
        val content = patch.applyTo(current.content)
        val selectedId = current.content.actions
            .getOrNull(current.selectedActionIndex)
            ?.id
        val selectedIndex = selectedId
            ?.let { id -> content.actions.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val nextOrder = ++order
        residents[surfaceId] = current.copy(
            seq = seq,
            content = content,
            lastUpdatedOrder = nextOrder,
            lastSignificantOrder = if (patch.significant) {
                nextOrder
            } else {
                current.lastSignificantOrder
            },
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            selectedActionIndex = selectedIndex,
            motionToken = ++motionToken,
        )
        return ActivityMutation.Applied(
            surfaceId = surfaceId,
            significant = patch.significant,
        )
    }

    fun end(surfaceId: String, seq: Long): ActivityMutation {
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq
        val removed = residents.remove(surfaceId) ?: return ActivityMutation.Ignored
        corners = corners - removed.surfaceId
        return ActivityMutation.Removed(removed.surfaceId)
    }

    /**
     * A reserved hub-owned `/activity/end` is the multi-slot empty assertion.
     * Its sequence is also a watermark, so delayed starts from before reconnect
     * cannot recreate a ghost after the clear.
     */
    fun clearAll(seq: Long): ActivityMutation {
        if (seq <= globalClearSeq) return ActivityMutation.DroppedStale
        globalClearSeq = seq
        val cleared = residents.keys.toList()
        residents.clear()
        corners = emptyMap()
        return ActivityMutation.Cleared(cleared)
    }

    fun expire(nowMs: Long): List<String> {
        val expired = residents.values
            .filter { it.maxDurationDeadlineMs?.let { deadline -> nowMs >= deadline } == true }
            .map(Resident::surfaceId)
        expired.forEach(residents::remove)
        if (expired.isNotEmpty()) corners = corners - expired.toSet()
        return expired
    }

    fun nextDeadlineMs(nowMs: Long, alwaysExpanded: Boolean): Long? = buildList {
        residents.values.forEach { resident ->
            resident.maxDurationDeadlineMs?.let(::add)
            if (!alwaysExpanded && resident.collapseAtMs > nowMs) add(resident.collapseAtMs)
        }
    }.minOrNull()

    fun presentationForEvent(
        surfaceId: String,
        context: ActivityPresentationContext,
        significant: Boolean,
        nowMs: Long,
        alwaysExpanded: Boolean,
    ): ActivityPresentation {
        val resident = residents[surfaceId] ?: return ActivityPresentation.HIDDEN
        if (context == ActivityPresentationContext.CAMERA_OVERLAY) {
            return ActivityPresentation.HIDDEN
        }
        if (surfaceId != primarySurfaceId()) return ActivityPresentation.PULSE
        val flareAvailable = resident.lastFlareAtMs
            ?.let { nowMs - it >= FLARE_INTERVAL_MS }
            ?: true
        val selected = selectActivityPresentation(
            context = context,
            significant = significant,
            flareBudgetAvailable = flareAvailable,
            collapseState = collapseState(resident, nowMs, alwaysExpanded),
        )
        if (selected == ActivityPresentation.FLARE) {
            residents[surfaceId] = resident.copy(lastFlareAtMs = nowMs)
        }
        return selected
    }

    fun snapshot(
        nowMs: Long,
        context: ActivityPresentationContext,
        pinCorner: PinSurfacePosition?,
        alwaysExpanded: Boolean,
        eventSurfaceId: String? = null,
        eventPresentation: ActivityPresentation? = null,
    ): ActivityRenderState {
        val ordered = residents.values.sortedBy(Resident::startedOrder)
        corners = allocateActivityCorners(
            activityIdsInOrder = ordered.map(Resident::surfaceId),
            existing = corners,
            pinCorner = pinCorner,
        )
        val primary = primarySurfaceId()
        return ActivityRenderState(
            ordered.mapNotNull { resident ->
                val corner = corners[resident.surfaceId] ?: return@mapNotNull null
                val presentation = when {
                    context == ActivityPresentationContext.CAMERA_OVERLAY ->
                        ActivityPresentation.HIDDEN
                    resident.surfaceId == eventSurfaceId && eventPresentation != null ->
                        eventPresentation
                    resident.surfaceId != primary ->
                        ActivityPresentation.CHIP
                    else ->
                        selectActivityPresentation(
                            context = context,
                            significant = false,
                            flareBudgetAvailable = false,
                            collapseState = collapseState(resident, nowMs, alwaysExpanded),
                        )
                }
                ActivityRenderItem(
                    activity = NexusActivitySurface(
                        surfaceId = resident.surfaceId,
                        ownerPluginId = resident.ownerPluginId,
                        seq = resident.seq,
                        content = resident.content,
                        corner = corner,
                        startedOrder = resident.startedOrder,
                        lastUpdatedOrder = resident.lastUpdatedOrder,
                        lastSignificantOrder = resident.lastSignificantOrder,
                        collapseAtMs = resident.collapseAtMs,
                        maxDurationDeadlineMs = resident.maxDurationDeadlineMs,
                        selectedActionIndex = resident.selectedActionIndex,
                        motionToken = resident.motionToken,
                    ),
                    primary = resident.surfaceId == primary,
                    presentation = presentation,
                )
            },
        )
    }

    fun moveSelection(surfaceId: String, delta: Int, nowMs: Long): Boolean {
        val current = residents[surfaceId] ?: return false
        val count = current.content.actions.size
        if (count == 0) return false
        val next = (current.selectedActionIndex + delta + count) % count
        residents[surfaceId] = current.copy(
            selectedActionIndex = next,
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            motionToken = ++motionToken,
        )
        return true
    }

    fun hasActions(surfaceId: String): Boolean =
        residents[surfaceId]?.content?.actions?.isNotEmpty() == true

    fun selectedAction(surfaceId: String) =
        residents[surfaceId]?.let { it.content.actions.getOrNull(it.selectedActionIndex) }

    fun ownerPluginId(surfaceId: String): String? = residents[surfaceId]?.ownerPluginId

    private fun isStale(surfaceId: String, seq: Long): Boolean =
        seq <= globalClearSeq || seq <= (latestSeqBySurface[surfaceId] ?: Long.MIN_VALUE)

    private fun evictionCandidate(): String {
        val primary = primarySurfaceId()
        return residents.values
            .filter { it.surfaceId != primary }
            .ifEmpty { residents.values.toList() }
            .minWith(
                compareBy<Resident> { it.lastUpdatedOrder }
                    .thenBy { it.startedOrder }
                    .thenBy { it.surfaceId },
            )
            .surfaceId
    }

    private fun collapseState(
        resident: Resident,
        nowMs: Long,
        alwaysExpanded: Boolean,
    ): ActivityCollapseState = when {
        alwaysExpanded -> ActivityCollapseState.ALWAYS_EXPANDED
        nowMs >= resident.collapseAtMs -> ActivityCollapseState.ELAPSED
        else -> ActivityCollapseState.RUNNING
    }

    companion object {
        const val COLLAPSE_AFTER_MS = 10_000L
        const val FLARE_INTERVAL_MS = 10_000L
    }
}

internal object ActivityPresentationSettings {
    private const val PREFS = "activity_presentation"
    private const val ALWAYS_EXPANDED = "always_expanded"

    fun alwaysExpanded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ALWAYS_EXPANDED, false)

    fun setAlwaysExpanded(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALWAYS_EXPANDED, enabled)
            .apply()
    }
}

internal object ActivityController {
    private val main = Handler(Looper.getMainLooper())
    private val state = ActivityStateMachine()
    private val listeners = CopyOnWriteArrayList<(ActivityRenderState) -> Unit>()
    private val inputDedupe = DpadPairDedupe()
    private val ringTapPolicy = RingTapPolicy()
    private val ringTapExpiry = Runnable(::resolveRingTap)
    private var deadlineTask: Runnable? = null
    private var context: Context? = null
    private var surfaceUnsubscribe: (() -> Unit)? = null
    private var pinUnsubscribe: (() -> Unit)? = null
    private var cameraOverlayActive = false
    private var latestRender = ActivityRenderState()

    fun onServiceConnected(context: Context) {
        runOnMain {
            this.context = context.applicationContext
            surfaceUnsubscribe?.invoke()
            pinUnsubscribe?.invoke()
            surfaceUnsubscribe = SurfaceController.observe { contextChanged() }
            pinUnsubscribe = PinController.observe { contextChanged() }
            publish()
        }
    }

    fun onServiceDestroyed() {
        runOnMain {
            surfaceUnsubscribe?.invoke()
            surfaceUnsubscribe = null
            pinUnsubscribe?.invoke()
            pinUnsubscribe = null
            cancelRingInput()
            cancelDeadline()
            context = null
        }
    }

    fun observe(listener: (ActivityRenderState) -> Unit): () -> Unit {
        listeners += listener
        listener(latestRender)
        return { listeners.remove(listener) }
    }

    fun handleActivityEnvelope(envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.ACTIVITY_START -> {
            runOnMain { start(envelope) }
            true
        }
        BusPaths.ACTIVITY_UPDATE -> {
            runOnMain { update(envelope) }
            true
        }
        BusPaths.ACTIVITY_END -> {
            runOnMain { end(envelope) }
            true
        }
        else -> false
    }

    fun setCameraOverlayActive(active: Boolean) {
        runOnMain {
            if (cameraOverlayActive == active) return@runOnMain
            cameraOverlayActive = active
            contextChanged()
        }
    }

    fun onLauncherVisibilityChanged() {
        runOnMain(::contextChanged)
    }

    fun setAlwaysExpanded(enabled: Boolean) {
        val appContext = context ?: return
        ActivityPresentationSettings.setAlwaysExpanded(appContext, enabled)
        runOnMain(::contextChanged)
    }

    fun claimsInput(): Boolean =
        latestRender.primary != null &&
            !cameraOverlayActive &&
            SurfaceController.activeSurface() == null &&
            NoticeController.visibleNotice() == null &&
            !LauncherOverlayRenderer.isShown()

    fun claimsRingKey(keyCode: Int): Boolean {
        if (!claimsInput()) return false
        return when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_TAP -> true
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD,
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD,
            -> latestRender.primary
                ?.activity
                ?.surfaceId
                ?.let(state::hasActions) == true
            else -> false
        }
    }

    /**
     * Claims only activity directions and confirmation. BACK and unrelated keys
     * continue down the pre-existing chain unchanged.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!claimsInput()) return false
        if (event.keyCode == TripleTapDetector.KEYCODE_NOTIFICATION) {
            return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        when (inputDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)) {
            DpadPairDedupe.Direction.FORWARD -> return moveSelection(1)
            DpadPairDedupe.Direction.BACKWARD -> return moveSelection(-1)
            null -> Unit
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> fireOrOpen()
            else -> false
        }
    }

    fun handlePendingTempleTap(): Boolean =
        if (claimsInput()) fireOrOpen() else false

    fun handleRingKey(keyCode: Int, eventTimeMs: Long): Boolean {
        if (!claimsRingKey(keyCode)) return false
        return when (keyCode) {
            RingSurfaceInputPolicy.RING_KEYCODE_FORWARD -> moveSelection(1)
            RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD -> moveSelection(-1)
            RingSurfaceInputPolicy.RING_KEYCODE_TAP -> {
                ringTapPolicy.onTap(eventTimeMs)
                main.removeCallbacks(ringTapExpiry)
                main.postDelayed(ringTapExpiry, RingTapPolicy.DEFAULT_WINDOW_MS + 1L)
                true
            }
            else -> false
        }
    }

    fun cancelRingInput() {
        main.removeCallbacks(ringTapExpiry)
        ringTapPolicy.reset()
    }

    private fun start(envelope: BusEnvelope) {
        val payload = envelope.payload
        val validation = ActivitySurfaceContract.validateStart(payload)
        if (validation !is ActivitySurfaceValidationResult.Valid) {
            log("activity rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val identity = identity(payload) ?: run {
            log("activity rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (
            val result = state.start(
                surfaceId = identity.first,
                ownerPluginId = identity.second,
                seq = payload.optLong("seq", Long.MIN_VALUE),
                content = validation.content,
                nowMs = now,
            )
        ) {
            is ActivityMutation.Applied -> {
                result.replacedSurfaceId?.let {
                    reportClosed(it, ActivityCloseReason.REPLACED)
                }
                publishEvent(result, now)
            }
            ActivityMutation.DroppedStale ->
                log("activity dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun update(envelope: BusEnvelope) {
        val payload = envelope.payload
        val validation = ActivitySurfaceContract.validateUpdate(payload)
        if (validation !is ActivitySurfacePatchResult.Valid) {
            log("activity update rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val identity = identity(payload) ?: run {
            log("activity update rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (
            val result = state.update(
                surfaceId = identity.first,
                seq = payload.optLong("seq", Long.MIN_VALUE),
                patch = validation.patch,
                nowMs = now,
            )
        ) {
            is ActivityMutation.Applied -> publishEvent(result, now)
            ActivityMutation.DroppedStale ->
                log("activity update dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun end(envelope: BusEnvelope) {
        val payload = envelope.payload
        val seq = payload.optLong("seq", Long.MIN_VALUE)
        if (isEmptySlotAssert(payload)) {
            when (state.clearAll(seq)) {
                is ActivityMutation.Cleared -> publish()
                ActivityMutation.DroppedStale -> log("activity empty assert dropped stale")
                else -> Unit
            }
            return
        }
        val identity = identity(payload) ?: return
        when (state.end(identity.first, seq)) {
            is ActivityMutation.Removed -> publish()
            ActivityMutation.DroppedStale ->
                log("activity end dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun publishEvent(result: ActivityMutation.Applied, nowMs: Long) {
        val presentation = state.presentationForEvent(
            surfaceId = result.surfaceId,
            context = presentationContext(),
            significant = result.significant,
            nowMs = nowMs,
            alwaysExpanded = alwaysExpanded(),
        )
        if (result.significant && presentation == ActivityPresentation.PULSE) {
            log("activity flare throttled id=${result.surfaceId}")
        }
        publish(
            eventSurfaceId = result.surfaceId,
            eventPresentation = presentation,
            nowMs = nowMs,
        )
    }

    private fun publish(
        eventSurfaceId: String? = null,
        eventPresentation: ActivityPresentation? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        latestRender = state.snapshot(
            nowMs = nowMs,
            context = presentationContext(),
            pinCorner = PinController.activePin()?.content?.position,
            alwaysExpanded = alwaysExpanded(),
            eventSurfaceId = eventSurfaceId,
            eventPresentation = eventPresentation,
        )
        listeners.forEach { listener -> runCatching { listener(latestRender) } }
        scheduleDeadline(nowMs)
    }

    private fun contextChanged() {
        publish()
    }

    private fun presentationContext(): ActivityPresentationContext = when {
        cameraOverlayActive -> ActivityPresentationContext.CAMERA_OVERLAY
        SurfaceController.activeSurface() != null -> ActivityPresentationContext.ACTIVE_SURFACE
        LauncherOverlayRenderer.isShown() -> ActivityPresentationContext.NEXUS_LAUNCHER
        else -> ActivityPresentationContext.IDLE_OR_NATIVE_HOME
    }

    private fun alwaysExpanded(): Boolean =
        context?.let(ActivityPresentationSettings::alwaysExpanded) ?: false

    private fun scheduleDeadline(nowMs: Long) {
        cancelDeadline()
        val deadline = state.nextDeadlineMs(nowMs, alwaysExpanded()) ?: return
        val task = Runnable {
            deadlineTask = null
            val expired = state.expire(SystemClock.elapsedRealtime())
            expired.forEach { reportClosed(it, ActivityCloseReason.MAX_DURATION) }
            publish()
        }
        deadlineTask = task
        main.postDelayed(task, (deadline - nowMs).coerceAtLeast(0L))
    }

    private fun cancelDeadline() {
        deadlineTask?.let(main::removeCallbacks)
        deadlineTask = null
    }

    private fun moveSelection(delta: Int): Boolean {
        val surfaceId = latestRender.primary?.activity?.surfaceId ?: return false
        val now = SystemClock.elapsedRealtime()
        if (!state.moveSelection(surfaceId, delta, now)) return false
        publish(nowMs = now)
        return true
    }

    private fun fireOrOpen(): Boolean {
        val surfaceId = latestRender.primary?.activity?.surfaceId ?: return false
        val action = state.selectedAction(surfaceId)
        if (action != null) {
            GlassesHub.sendToPhone(
                BusPaths.ACTIVITY_ACTION,
                ActivitySurfaceContract.actionPayload(surfaceId, action.id),
            )
            return true
        }
        val owner = state.ownerPluginId(surfaceId) ?: return false
        val result = GlassesHub.openLauncherEntry(owner)
        log("activity owner open result: $result")
        return true
    }

    private fun resolveRingTap() {
        when (ringTapPolicy.resolveExpired(SystemClock.elapsedRealtime())) {
            RingTapPolicy.Resolution.SINGLE -> fireOrOpen()
            // An activity never claims BACK. A double tap is therefore only a
            // cancelled confirmation, not a hidden dismissal gesture.
            RingTapPolicy.Resolution.DOUBLE,
            RingTapPolicy.Resolution.IGNORE,
            null,
            -> Unit
        }
    }

    private fun reportClosed(surfaceId: String, reason: ActivityCloseReason) {
        GlassesHub.sendToPhone(
            BusPaths.ACTIVITY_CLOSED,
            ActivitySurfaceContract.closedPayload(surfaceId, reason),
        )
    }

    private fun identity(payload: org.json.JSONObject): Pair<String, String>? {
        val surfaceId = payload.optString("surfaceId")
        val ownerPluginId = payload.optString("ownerPluginId")
        if (surfaceId.isBlank() || ownerPluginId.isBlank()) return null
        if (surfaceId != "$ownerPluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}") return null
        return surfaceId to ownerPluginId
    }

    private fun isEmptySlotAssert(payload: org.json.JSONObject): Boolean =
        payload.optString("ownerPluginId") == EMPTY_ASSERT_OWNER &&
            payload.optString("surfaceId") ==
            "$EMPTY_ASSERT_OWNER:${ActivitySurfaceContract.LOCAL_SURFACE_ID}"

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private const val EMPTY_ASSERT_OWNER = "nexus-hub"
}
