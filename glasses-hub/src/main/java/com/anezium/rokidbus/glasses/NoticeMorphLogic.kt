package com.anezium.rokidbus.glasses

import kotlin.math.roundToInt

/** Screen- or host-local child bounds used by the notice-to-Ink handoff. */
internal data class HudBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left)
        require(bottom >= top)
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun relativeTo(originX: Int, originY: Int): HudBounds = HudBounds(
        left = left - originX,
        top = top - originY,
        right = right - originX,
        bottom = bottom - originY,
    )
}

internal data class NoticeBandSnapshot(
    val surfaceId: String,
    val seq: Long,
    val ownerPluginId: String,
    val title: String?,
    val body: String?,
    val footer: String?,
    val bounds: HudBounds,
    val alpha: Float,
)

internal data class NoticeMorphFrame(
    val bounds: HudBounds,
    val noticeAlpha: Float,
    val inkAlpha: Float,
)

/** This choreography is deliberately Assistant-only; every other notice remains unchanged. */
internal fun noticeMatchesInkOwner(
    noticeOwnerPluginId: String?,
    surfaceOwnerPluginId: String,
): Boolean = surfaceOwnerPluginId == ASSISTANT_PLUGIN_ID && noticeOwnerPluginId == surfaceOwnerPluginId

internal fun noticeMorphFrame(
    start: HudBounds,
    target: HudBounds,
    progress: Float,
    initialNoticeAlpha: Float = 1f,
): NoticeMorphFrame {
    val amount = progress.coerceIn(0f, 1f)
    return NoticeMorphFrame(
        bounds = HudBounds(
            left = lerp(start.left, target.left, amount),
            top = lerp(start.top, target.top, amount),
            right = lerp(start.right, target.right, amount),
            bottom = lerp(start.bottom, target.bottom, amount),
        ),
        noticeAlpha = initialNoticeAlpha.coerceIn(0f, 1f) * (1f - amount),
        inkAlpha = amount,
    )
}

private fun lerp(start: Int, target: Int, amount: Float): Int =
    (start + (target - start) * amount).roundToInt()

private const val ASSISTANT_PLUGIN_ID = "assistant"
