package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.EnumMap

internal class SelfArmVerifiedSettingsScroller(
    private val service: AccessibilityService,
) {
    internal enum class Surface {
        WIFI_SETTINGS,
        DEVELOPER_OPTIONS,
        DEVICE_INFO,
        WIRELESS_DEBUGGING,
    }

    internal enum class Outcome {
        WAITING,
        MOVED,
        PHASE_CHANGED,
        EXHAUSTED,
    }

    val settleDelayMs: Long
        get() = SCROLL_SETTLE_MS

    private var pendingAttempt: PendingAttempt? = null
    private val searchStates = EnumMap<Surface, SearchState>(Surface::class.java)

    fun resetAll() {
        searchStates.clear()
        Surface.entries.forEach { searchStates[it] = SearchState() }
        clearPending()
    }

    fun reset(surface: Surface) {
        searchStates[surface] = SearchState()
        if (pendingAttempt?.surface == surface) clearPending()
    }

    fun clearPending() {
        pendingAttempt = null
    }

    fun continueSearch(
        root: AccessibilityNodeInfo,
        surface: Surface,
        maxBack: Int,
        maxForward: Int,
    ): Outcome {
        val state = searchStates.getOrPut(surface, ::SearchState)
        if (
            state.phase == SearchPhase.RETURN_TO_START &&
            state.backwardMoves >= maxBack
        ) {
            state.phase = SearchPhase.SEARCH_FORWARD
            clearPending()
            return Outcome.PHASE_CHANGED
        }
        if (
            state.phase == SearchPhase.SEARCH_FORWARD &&
            state.forwardMoves >= maxForward
        ) {
            clearPending()
            return Outcome.EXHAUSTED
        }
        val direction = when (state.phase) {
            SearchPhase.RETURN_TO_START -> SelfArmAccessibilityScrollDirection.BACKWARD
            SearchPhase.SEARCH_FORWARD -> SelfArmAccessibilityScrollDirection.FORWARD
        }
        return when (performVerifiedScroll(root, surface, direction)) {
            VerifiedOutcome.WAITING -> Outcome.WAITING
            VerifiedOutcome.MOVED -> {
                if (state.phase == SearchPhase.RETURN_TO_START) {
                    state.backwardMoves++
                } else {
                    state.forwardMoves++
                }
                Outcome.MOVED
            }
            VerifiedOutcome.NO_PROGRESS -> {
                if (state.phase == SearchPhase.RETURN_TO_START) {
                    state.phase = SearchPhase.SEARCH_FORWARD
                    clearPending()
                    Outcome.PHASE_CHANGED
                } else {
                    clearPending()
                    Outcome.EXHAUSTED
                }
            }
        }
    }

    private fun performVerifiedScroll(
        root: AccessibilityNodeInfo,
        surface: Surface,
        direction: SelfArmAccessibilityScrollDirection,
    ): VerifiedOutcome {
        val now = SystemClock.uptimeMillis()
        val projection = projectSettingsTree(root)
        try {
            val signature = SelfArmAccessibilityScrollStrategy.contentSignature(
                projection.nodes.map(ProjectedNode::snapshot),
                projection.windowBounds,
            )
            val pending = pendingAttempt
            if (pending != null && (pending.surface != surface || pending.direction != direction)) {
                clearPending()
            }
            pendingAttempt?.let { attempt ->
                if (now - attempt.startedAt < SCROLL_SETTLE_MS) {
                    return VerifiedOutcome.WAITING
                }
                if (
                    SelfArmAccessibilityScrollStrategy.compareProgress(
                        attempt.beforeSignature,
                        signature,
                    ) == SelfArmAccessibilityScrollProgress.MOVED
                ) {
                    clearPending()
                    return VerifiedOutcome.MOVED
                }
                if (!attempt.gestureFallbackUsed) {
                    val gestureDispatched = dispatchScrollGesture(projection, direction)
                    if (gestureDispatched) {
                        pendingAttempt = attempt.copy(
                            beforeSignature = signature,
                            gestureFallbackUsed = true,
                            startedAt = now,
                        )
                        return VerifiedOutcome.WAITING
                    }
                }
                Log.w(
                    TAG,
                    "Settings scroll made no progress surface=$surface direction=$direction " +
                        "gestureFallback=${attempt.gestureFallbackUsed}",
                )
                clearPending()
                return VerifiedOutcome.NO_PROGRESS
            }

            val target = selectProjectedScrollable(projection)
                ?: return VerifiedOutcome.NO_PROGRESS
            val action = when (direction) {
                SelfArmAccessibilityScrollDirection.BACKWARD ->
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                SelfArmAccessibilityScrollDirection.FORWARD ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val actionAccepted =
                runCatching { target.node.performAction(action) }.getOrDefault(false)
            if (actionAccepted) {
                pendingAttempt = PendingAttempt(
                    surface = surface,
                    direction = direction,
                    beforeSignature = signature,
                    gestureFallbackUsed = false,
                    startedAt = now,
                )
                return VerifiedOutcome.WAITING
            }
            if (dispatchScrollGesture(projection, direction)) {
                pendingAttempt = PendingAttempt(
                    surface = surface,
                    direction = direction,
                    beforeSignature = signature,
                    gestureFallbackUsed = true,
                    startedAt = now,
                )
                return VerifiedOutcome.WAITING
            }
            return VerifiedOutcome.NO_PROGRESS
        } finally {
            projection.close()
        }
    }

    private fun dispatchScrollGesture(
        projection: ProjectedTree,
        direction: SelfArmAccessibilityScrollDirection,
    ): Boolean {
        val target = selectProjectedScrollable(projection) ?: return false
        val swipe = when (direction) {
            SelfArmAccessibilityScrollDirection.BACKWARD ->
                SelfArmAccessibilityScrollStrategy.backwardSwipe(
                    target.snapshot.bounds,
                    projection.windowBounds,
                )
            SelfArmAccessibilityScrollDirection.FORWARD ->
                SelfArmAccessibilityScrollStrategy.forwardSwipe(
                    target.snapshot.bounds,
                    projection.windowBounds,
                )
        } ?: return false
        return runCatching {
            val path = Path().apply {
                moveTo(swipe.startX.toFloat(), swipe.startY.toFloat())
                lineTo(swipe.endX.toFloat(), swipe.endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        swipe.durationMs,
                    ),
                )
                .build()
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun projectSettingsTree(root: AccessibilityNodeInfo): ProjectedTree {
        val windowBounds = screenBounds(root)
        val defaultPackage = runCatching { root.packageName?.toString() }.getOrNull()
        val nodes = ArrayList<ProjectedNode>()
        val ownedNodes = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || nodes.size >= MAX_SCROLL_SIGNATURE_NODES) return
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            runCatching {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val textToken = listOf(
                    node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty(),
                    node.stateDescription?.toString().orEmpty(),
                    node.isChecked.toString(),
                    node.isEnabled.toString(),
                    childCount.toString(),
                ).joinToString("\u001f")
                ProjectedNode(
                    node = node,
                    snapshot = SelfArmAccessibilityNodeSnapshot(
                        packageName = node.packageName?.toString() ?: defaultPackage,
                        viewIdResourceName = node.viewIdResourceName,
                        className = node.className?.toString(),
                        bounds = SelfArmScreenBounds(
                            bounds.left,
                            bounds.top,
                            bounds.right,
                            bounds.bottom,
                        ),
                        visibleToUser = node.isVisibleToUser,
                        scrollable = node.isScrollable,
                        contentToken = textToken,
                    ),
                )
            }.getOrNull()?.let(nodes::add)
            for (index in 0 until childCount) {
                if (nodes.size >= MAX_SCROLL_SIGNATURE_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                ownedNodes += child
                visit(child)
            }
        }
        return try {
            visit(root)
            ProjectedTree(windowBounds, nodes, ownedNodes)
        } catch (error: Throwable) {
            ownedNodes.asReversed().forEach { node -> runCatching { node.recycle() } }
            throw error
        }
    }

    private fun selectProjectedScrollable(projection: ProjectedTree): ProjectedNode? {
        val selected = SelfArmAccessibilityScrollStrategy.selectBestScrollable(
            projection.nodes.map(ProjectedNode::snapshot),
            projection.windowBounds,
        ) ?: return null
        return projection.nodes.firstOrNull { it.snapshot === selected }
    }

    private fun screenBounds(root: AccessibilityNodeInfo): SelfArmScreenBounds {
        val rootBounds = Rect()
        runCatching { root.getBoundsInScreen(rootBounds) }
        if (!rootBounds.isEmpty) {
            return SelfArmScreenBounds(
                rootBounds.left,
                rootBounds.top,
                rootBounds.right,
                rootBounds.bottom,
            )
        }
        val metrics = service.resources.displayMetrics
        return SelfArmScreenBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private enum class SearchPhase {
        RETURN_TO_START,
        SEARCH_FORWARD,
    }

    private enum class VerifiedOutcome {
        WAITING,
        MOVED,
        NO_PROGRESS,
    }

    private data class SearchState(
        var phase: SearchPhase = SearchPhase.RETURN_TO_START,
        var backwardMoves: Int = 0,
        var forwardMoves: Int = 0,
    )

    private data class PendingAttempt(
        val surface: Surface,
        val direction: SelfArmAccessibilityScrollDirection,
        val beforeSignature: SelfArmAccessibilityContentSignature?,
        val gestureFallbackUsed: Boolean,
        val startedAt: Long,
    )

    private data class ProjectedNode(
        val node: AccessibilityNodeInfo,
        val snapshot: SelfArmAccessibilityNodeSnapshot,
    )

    private class ProjectedTree(
        val windowBounds: SelfArmScreenBounds,
        val nodes: List<ProjectedNode>,
        private val ownedNodes: List<AccessibilityNodeInfo>,
    ) : AutoCloseable {
        private var closed = false

        @Suppress("DEPRECATION")
        override fun close() {
            if (closed) return
            closed = true
            ownedNodes.asReversed().forEach { node -> runCatching { node.recycle() } }
        }
    }

    private companion object {
        const val TAG = "NexusWirelessSetup"
        const val SCROLL_SETTLE_MS = 360L
        const val MAX_SCROLL_SIGNATURE_NODES = 512
    }
}
