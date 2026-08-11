package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

internal enum class RemoteNavigationAction {
    PREVIOUS,
    NEXT,
    SELECT,
    BACK,
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

internal enum class RemoteNavigationResult {
    PERFORMED,
    SERVICE_UNAVAILABLE,
    NO_READABLE_WINDOW,
    NO_TARGET,
}

internal enum class RemoteNavigationStrategy {
    FOCUS_PREVIOUS,
    FOCUS_NEXT,
    CLICK_FOCUSED,
    GLOBAL_BACK,
    FOCUS_UP,
    FOCUS_DOWN,
    FOCUS_LEFT,
    FOCUS_RIGHT,
}

internal object RemoteNavigationPolicy {
    fun strategy(action: RemoteNavigationAction): RemoteNavigationStrategy = when (action) {
        RemoteNavigationAction.PREVIOUS -> RemoteNavigationStrategy.FOCUS_PREVIOUS
        RemoteNavigationAction.NEXT -> RemoteNavigationStrategy.FOCUS_NEXT
        RemoteNavigationAction.SELECT -> RemoteNavigationStrategy.CLICK_FOCUSED
        RemoteNavigationAction.BACK -> RemoteNavigationStrategy.GLOBAL_BACK
        RemoteNavigationAction.UP -> RemoteNavigationStrategy.FOCUS_UP
        RemoteNavigationAction.DOWN -> RemoteNavigationStrategy.FOCUS_DOWN
        RemoteNavigationAction.LEFT -> RemoteNavigationStrategy.FOCUS_LEFT
        RemoteNavigationAction.RIGHT -> RemoteNavigationStrategy.FOCUS_RIGHT
    }

    /**
     * Where a direction falls back when the layout offers nothing that way.
     *
     * A press that does nothing reads as a broken remote, and plenty of glasses
     * screens are a single column whose items only answer to forward/backward.
     */
    fun sequentialFallback(action: RemoteNavigationAction): Boolean? = when (action) {
        RemoteNavigationAction.DOWN, RemoteNavigationAction.RIGHT -> true
        RemoteNavigationAction.UP, RemoteNavigationAction.LEFT -> false
        else -> null
    }
}

/**
 * System-wide remote navigation backed entirely by AccessibilityService APIs. Text injection and
 * ADB are intentionally absent. The existing glasses AccessibilityService attaches itself here.
 */
internal object RemoteNavigationController {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var service: AccessibilityService? = null

    internal fun onServiceConnected(owner: AccessibilityService) {
        runOnMain { service = owner }
    }

    internal fun onServiceDestroyed(owner: AccessibilityService) {
        runOnMain {
            if (service === owner) service = null
        }
    }

    fun perform(
        action: RemoteNavigationAction,
        callback: (RemoteNavigationResult) -> Unit = {},
    ) {
        runOnMain {
            val owner = service
            if (owner == null) {
                callback(RemoteNavigationResult.SERVICE_UNAVAILABLE)
                return@runOnMain
            }
            // Someone is holding the phone and pressing a direction: that is a live
            // wearer, so the panel comes back on. Navigating a screen you cannot see
            // is the same as not navigating at all.
            DisplayWakePolicy.noteUserInteraction()
            DisplayWakePolicy.requestWake(owner, DisplayWakeKind.ACTIVITY, requested = true)
            callback(AccessibilityNodeNavigator(owner).perform(action))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

private class AccessibilityNodeNavigator(
    private val service: AccessibilityService,
) {
    fun perform(action: RemoteNavigationAction): RemoteNavigationResult {
        if (action == RemoteNavigationAction.BACK) {
            return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                RemoteNavigationResult.PERFORMED
            } else {
                RemoteNavigationResult.NO_TARGET
            }
        }

        val root = AccessibilityWindowRoots.getNavigationRoot(service)
            ?: return RemoteNavigationResult.NO_READABLE_WINDOW
        return try {
            when (action) {
                RemoteNavigationAction.PREVIOUS -> move(root, forward = false)
                RemoteNavigationAction.NEXT -> move(root, forward = true)
                RemoteNavigationAction.SELECT -> select(root)
                RemoteNavigationAction.BACK -> error("Handled above")
                RemoteNavigationAction.UP,
                RemoteNavigationAction.DOWN,
                RemoteNavigationAction.LEFT,
                RemoteNavigationAction.RIGHT,
                -> moveDirectional(root, action)
            }
        } finally {
            root.recycle()
        }
    }

    private fun moveDirectional(
        root: AccessibilityNodeInfo,
        action: RemoteNavigationAction,
    ): RemoteNavigationResult {
        val direction = when (action) {
            RemoteNavigationAction.UP -> View.FOCUS_UP
            RemoteNavigationAction.DOWN -> View.FOCUS_DOWN
            RemoteNavigationAction.LEFT -> View.FOCUS_LEFT
            RemoteNavigationAction.RIGHT -> View.FOCUS_RIGHT
            else -> error("Not a direction")
        }
        val focused = findFocused(root)
        try {
            if (focused != null && focusSearchDirection(focused, direction)) {
                return RemoteNavigationResult.PERFORMED
            }
        } finally {
            focused?.recycle()
        }
        val forward = RemoteNavigationPolicy.sequentialFallback(action)
            ?: return RemoteNavigationResult.NO_TARGET
        return move(root, forward)
    }

    private fun focusSearchDirection(node: AccessibilityNodeInfo, direction: Int): Boolean {
        val target = runCatching { node.focusSearch(direction) }.getOrNull() ?: return false
        return try {
            target.isVisibleToUser && target.isEnabled && focus(target)
        } finally {
            target.recycle()
        }
    }

    private fun move(root: AccessibilityNodeInfo, forward: Boolean): RemoteNavigationResult {
        val focused = findFocused(root)
        try {
            if (focused != null && focusSearch(focused, forward)) {
                return RemoteNavigationResult.PERFORMED
            }

            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectCandidates(root, candidates)
            try {
                val focusedIndex = focused?.let { current ->
                    candidates.indexOfFirst { candidate -> candidate == current }
                } ?: -1
                val targetIndex = when {
                    candidates.isEmpty() -> -1
                    focusedIndex < 0 && forward -> 0
                    focusedIndex < 0 -> candidates.lastIndex
                    forward && focusedIndex < candidates.lastIndex -> focusedIndex + 1
                    !forward && focusedIndex > 0 -> focusedIndex - 1
                    else -> -1
                }
                if (targetIndex >= 0 && focus(candidates[targetIndex])) {
                    return RemoteNavigationResult.PERFORMED
                }
                if (scroll(candidates, forward)) {
                    return RemoteNavigationResult.PERFORMED
                }
            } finally {
                candidates.forEach(AccessibilityNodeInfo::recycle)
            }
            return RemoteNavigationResult.NO_TARGET
        } finally {
            focused?.recycle()
        }
    }

    private fun select(root: AccessibilityNodeInfo): RemoteNavigationResult {
        val focused = findFocused(root) ?: return RemoteNavigationResult.NO_TARGET
        return try {
            if (clickSelfOrAncestor(focused)) {
                RemoteNavigationResult.PERFORMED
            } else {
                RemoteNavigationResult.NO_TARGET
            }
        } finally {
            focused.recycle()
        }
    }

    private fun findFocused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun focusSearch(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val direction = if (forward) View.FOCUS_FORWARD else View.FOCUS_BACKWARD
        val target = runCatching { node.focusSearch(direction) }.getOrNull() ?: return false
        return try {
            target.isVisibleToUser && target.isEnabled && focus(target)
        } finally {
            target.recycle()
        }
    }

    private fun focus(node: AccessibilityNodeInfo): Boolean {
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) ||
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (focused && node.packageName?.toString() == service.packageName) {
            node.actionList
                .firstOrNull { action -> action.label?.toString() == NEXUS_SELECT_ACTION_LABEL }
                ?.let { action -> node.performAction(action.id) }
        }
        return focused
    }

    private fun scroll(nodes: List<AccessibilityNodeInfo>, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return nodes.asSequence()
            .filter(AccessibilityNodeInfo::isScrollable)
            .any { node -> node.performAction(action) }
    }

    private fun clickSelfOrAncestor(start: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(start)
        while (current != null) {
            val candidate = current
            if (candidate.isEnabled && candidate.isVisibleToUser && candidate.isClickable) {
                val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                candidate.recycle()
                return clicked
            }
            current = candidate.parent
            candidate.recycle()
        }
        return false
    }

    private fun collectCandidates(
        node: AccessibilityNodeInfo,
        destination: MutableList<AccessibilityNodeInfo>,
    ) {
        if (isNavigationCandidate(node)) {
            destination += AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectCandidates(child, destination)
            } finally {
                child.recycle()
            }
        }
        destination.sortWith(
            compareBy<AccessibilityNodeInfo>(
                { boundsOf(it).top },
                { boundsOf(it).left },
                { boundsOf(it).bottom },
                { boundsOf(it).right },
            ),
        )
    }

    private fun isNavigationCandidate(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser &&
            node.isEnabled &&
            (node.isFocusable || node.isClickable || node.isEditable || node.isScrollable)

    private fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    companion object {
        private const val NEXUS_SELECT_ACTION_LABEL = "Select"
    }
}
