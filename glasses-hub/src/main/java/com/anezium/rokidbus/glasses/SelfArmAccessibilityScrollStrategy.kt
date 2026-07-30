package com.anezium.rokidbus.glasses

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class SelfArmScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun intersection(other: SelfArmScreenBounds): SelfArmScreenBounds? {
        val clipped = SelfArmScreenBounds(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
        return clipped.takeUnless { it.isEmpty }
    }

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * Android-free projection of the Accessibility tree fields needed for Settings scrolling.
 *
 * [contentToken] may contain normalized text/content-description data. It is only consumed by a
 * SHA-256 signature and is never returned in clear text.
 */
internal data class SelfArmAccessibilityNodeSnapshot(
    val packageName: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val bounds: SelfArmScreenBounds,
    val visibleToUser: Boolean,
    val scrollable: Boolean,
    val contentToken: String? = null,
)

internal data class SelfArmAccessibilitySwipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
    val usesWindowFallback: Boolean,
)

internal data class SelfArmAccessibilityContentSignature(
    val sha256: String,
)

internal enum class SelfArmAccessibilityScrollProgress {
    UNKNOWN,
    MOVED,
    NO_PROGRESS,
}

internal enum class SelfArmAccessibilityScrollDirection {
    BACKWARD,
    FORWARD,
}

/**
 * Pure selection, gesture and progress policy for the self-arm Settings automator.
 *
 * On affected YodaOS builds the Developer options app bar can leave the RecyclerView only four
 * pixels high (`636..640`). The forward gesture therefore starts inside that visible strip, but
 * may finish in the larger Settings window. Keeping the whole gesture in the window is the only
 * way to collapse the app bar while still anchoring the touch on the real scroll target.
 */
internal object SelfArmAccessibilityScrollStrategy {
    const val SETTINGS_PACKAGE = "com.android.settings"

    private const val MIN_USEFUL_TRAVEL_PX = 48
    private const val TINY_VIEWPORT_TOP_INSET_PX = 84
    private const val SWIPE_DURATION_MS = 180L
    private val whitespace = Regex("\\s+")

    fun selectBestScrollable(
        nodes: Iterable<SelfArmAccessibilityNodeSnapshot>,
        windowBounds: SelfArmScreenBounds,
    ): SelfArmAccessibilityNodeSnapshot? =
        nodes.asSequence()
            .mapNotNull { node ->
                if (
                    node.packageName != SETTINGS_PACKAGE ||
                    !node.visibleToUser ||
                    !node.scrollable
                ) {
                    return@mapNotNull null
                }
                val visibleBounds = node.bounds.intersection(windowBounds)
                    ?: return@mapNotNull null
                RankedScrollable(
                    node = node,
                    visibleBounds = visibleBounds,
                    idPriority = idPriority(node.viewIdResourceName),
                    classPriority = classPriority(node.className),
                )
            }
            .sortedWith(
                compareByDescending<RankedScrollable> { it.semanticPriority }
                    .thenByDescending { it.idPriority }
                    .thenByDescending { it.classPriority }
                    .thenByDescending { it.visibleBounds.width }
                    .thenByDescending { it.visibleBounds.area }
                    .thenBy { it.visibleBounds.top }
                    .thenBy { it.visibleBounds.left }
                    .thenBy { it.node.viewIdResourceName.orEmpty() }
                    .thenBy { it.node.className.orEmpty() },
            )
            .firstOrNull()
            ?.node

    /**
     * Builds an upward swipe whose start is always inside the visible scroll target.
     *
     * For a normal viewport both endpoints stay in the target. For a viewport too short to
     * produce a useful gesture, the endpoint expands upward into [windowBounds], while the full
     * gesture remains inside that Settings window.
     */
    fun forwardSwipe(
        targetBounds: SelfArmScreenBounds,
        windowBounds: SelfArmScreenBounds,
    ): SelfArmAccessibilitySwipe? =
        swipe(
            direction = SelfArmAccessibilityScrollDirection.FORWARD,
            targetBounds = targetBounds,
            windowBounds = windowBounds,
        )

    /**
     * Builds a downward swipe for returning toward the start of a Settings list.
     *
     * A four-pixel viewport at the bottom of the display cannot provide useful downward travel;
     * callers should try ACTION_SCROLL_BACKWARD first and treat a null gesture as "already at the
     * start or action-only". Once the app bar is collapsed, the normal viewport is large enough.
     */
    fun backwardSwipe(
        targetBounds: SelfArmScreenBounds,
        windowBounds: SelfArmScreenBounds,
    ): SelfArmAccessibilitySwipe? =
        swipe(
            direction = SelfArmAccessibilityScrollDirection.BACKWARD,
            targetBounds = targetBounds,
            windowBounds = windowBounds,
        )

    private fun swipe(
        direction: SelfArmAccessibilityScrollDirection,
        targetBounds: SelfArmScreenBounds,
        windowBounds: SelfArmScreenBounds,
    ): SelfArmAccessibilitySwipe? {
        val visibleTarget = targetBounds.intersection(windowBounds) ?: return null
        val startX = midpointInside(visibleTarget.left, visibleTarget.right)
        val startY: Int
        val endY: Int
        val usesWindowFallback: Boolean
        when (direction) {
            SelfArmAccessibilityScrollDirection.FORWARD -> {
                startY = visibleTarget.bottom - 1
                val targetEndY = visibleTarget.top
                usesWindowFallback = startY - targetEndY < MIN_USEFUL_TRAVEL_PX
                endY = if (usesWindowFallback) {
                    val scaledTopInset = (windowBounds.height / 4).coerceAtLeast(1)
                    windowBounds.top + minOf(TINY_VIEWPORT_TOP_INSET_PX, scaledTopInset)
                } else {
                    targetEndY
                }
            }
            SelfArmAccessibilityScrollDirection.BACKWARD -> {
                startY = visibleTarget.top
                endY = visibleTarget.bottom - 1
                usesWindowFallback = false
            }
        }
        val travel = when (direction) {
            SelfArmAccessibilityScrollDirection.FORWARD -> startY - endY
            SelfArmAccessibilityScrollDirection.BACKWARD -> endY - startY
        }
        if (travel < MIN_USEFUL_TRAVEL_PX) return null
        if (!windowBounds.contains(startX, startY) || !windowBounds.contains(startX, endY)) {
            return null
        }
        return SelfArmAccessibilitySwipe(
            startX = startX,
            startY = startY,
            endX = startX,
            endY = endY,
            durationMs = SWIPE_DURATION_MS,
            usesWindowFallback = usesWindowFallback,
        )
    }

    /**
     * Hashes the visible Settings tree in a stable order so callers can compare before/after
     * snapshots without retaining or logging UI text.
     */
    fun contentSignature(
        nodes: Iterable<SelfArmAccessibilityNodeSnapshot>,
        windowBounds: SelfArmScreenBounds,
    ): SelfArmAccessibilityContentSignature? {
        val records = nodes.asSequence()
            .mapNotNull { node ->
                if (node.packageName != SETTINGS_PACKAGE || !node.visibleToUser) {
                    return@mapNotNull null
                }
                val visibleBounds = node.bounds.intersection(windowBounds)
                    ?: return@mapNotNull null
                encodeRecord(node, visibleBounds)
            }
            .sorted()
            .toList()
        if (records.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(records.joinToString(RECORD_SEPARATOR).toByteArray(StandardCharsets.UTF_8))
        return SelfArmAccessibilityContentSignature(digest.toHex())
    }

    fun compareProgress(
        before: SelfArmAccessibilityContentSignature?,
        after: SelfArmAccessibilityContentSignature?,
    ): SelfArmAccessibilityScrollProgress =
        when {
            before == null || after == null -> SelfArmAccessibilityScrollProgress.UNKNOWN
            before == after -> SelfArmAccessibilityScrollProgress.NO_PROGRESS
            else -> SelfArmAccessibilityScrollProgress.MOVED
        }

    private fun encodeRecord(
        node: SelfArmAccessibilityNodeSnapshot,
        visibleBounds: SelfArmScreenBounds,
    ): String =
        listOf(
            normalized(node.viewIdResourceName),
            normalized(node.className),
            visibleBounds.left.toString(),
            visibleBounds.top.toString(),
            visibleBounds.right.toString(),
            visibleBounds.bottom.toString(),
            node.scrollable.toString(),
            normalized(node.contentToken),
        ).joinToString(FIELD_SEPARATOR) { value -> "${value.length}:$value" }

    private fun normalized(value: String?): String =
        value.orEmpty().trim().replace(whitespace, " ")

    private fun idPriority(value: String?): Int {
        val id = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            id.endsWith(":id/recycler_view") || id.endsWith("/recycler_view") -> 500
            "recycler" in id -> 450
            id.endsWith(":id/list") || id.endsWith("/list") -> 400
            "list" in id -> 300
            "content" in id -> 200
            id.isNotBlank() -> 100
            else -> 0
        }
    }

    private fun classPriority(value: String?): Int {
        val className = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            className.endsWith("recyclerview") -> 500
            className.endsWith("listview") -> 400
            className.endsWith("nestedscrollview") -> 300
            className.endsWith("scrollview") -> 250
            "viewpager" in className -> 100
            else -> 0
        }
    }

    private fun midpointInside(start: Int, end: Int): Int =
        ((start.toLong() + end.toLong()) / 2L).toInt().coerceIn(start, end - 1)

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            this@toHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    private data class RankedScrollable(
        val node: SelfArmAccessibilityNodeSnapshot,
        val visibleBounds: SelfArmScreenBounds,
        val idPriority: Int,
        val classPriority: Int,
    ) {
        val semanticPriority: Int get() = idPriority + classPriority
    }

    private const val FIELD_SEPARATOR = "\u001f"
    private const val RECORD_SEPARATOR = "\u001e"
}
