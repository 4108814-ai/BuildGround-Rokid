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
    /** The Ink view's real, unchanged layout rectangle. */
    val layoutBounds: HudBounds,
    val pivotX: Float,
    val pivotY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val noticeAlpha: Float,
    val inkAlpha: Float,
)

internal enum class NoticeMorphPhase {
    WAITING_FOR_FIRST_FRAME,
    ANIMATING,
    COMPLETE,
    CANCELLED,
}

/** The notice may only close after Ink readiness has started and completed the morph. */
internal class NoticeMorphLifecycle {
    var phase: NoticeMorphPhase = NoticeMorphPhase.WAITING_FOR_FIRST_FRAME
        private set

    fun onFirstFrame(): Boolean {
        if (phase != NoticeMorphPhase.WAITING_FOR_FIRST_FRAME) return false
        phase = NoticeMorphPhase.ANIMATING
        return true
    }

    fun onAnimationComplete(): Boolean {
        if (phase != NoticeMorphPhase.ANIMATING) return false
        phase = NoticeMorphPhase.COMPLETE
        return true
    }

    fun cancel() {
        if (phase != NoticeMorphPhase.COMPLETE) phase = NoticeMorphPhase.CANCELLED
    }
}

/** This choreography is deliberately Assistant-only; every other notice remains unchanged. */
internal fun noticeMatchesInkOwner(
    noticeOwnerPluginId: String?,
    surfaceOwnerPluginId: String,
): Boolean = surfaceOwnerPluginId == ASSISTANT_PLUGIN_ID && noticeOwnerPluginId == surfaceOwnerPluginId

internal fun noticeMorphFrame(
    inkLayout: HudBounds,
    start: HudBounds,
    target: HudBounds,
    progress: Float,
    initialNoticeAlpha: Float = 1f,
): NoticeMorphFrame? {
    if (inkLayout.isEmpty || start.isEmpty || target.isEmpty) return null
    val amount = progress.coerceIn(0f, 1f)
    val startScaleX = start.width.toFloat() / target.width.toFloat()
    val startScaleY = start.height.toFloat() / target.height.toFloat()
    return NoticeMorphFrame(
        layoutBounds = inkLayout,
        pivotX = (target.left - inkLayout.left).toFloat(),
        pivotY = (target.top - inkLayout.top).toFloat(),
        scaleX = lerp(startScaleX, 1f, amount),
        scaleY = lerp(startScaleY, 1f, amount),
        translationX = lerp((start.left - target.left).toFloat(), 0f, amount),
        translationY = lerp((start.top - target.top).toFloat(), 0f, amount),
        noticeAlpha = initialNoticeAlpha.coerceIn(0f, 1f) * (1f - amount),
        inkAlpha = amount,
    )
}

internal fun NoticeMorphFrame.visualContentBounds(target: HudBounds): HudBounds = HudBounds(
    left = (target.left + translationX).roundToInt(),
    top = (target.top + translationY).roundToInt(),
    right = (target.left + translationX + target.width * scaleX).roundToInt(),
    bottom = (target.top + translationY + target.height * scaleY).roundToInt(),
)

private fun lerp(start: Float, target: Float, amount: Float): Float =
    start + (target - start) * amount

private const val ASSISTANT_PLUGIN_ID = "assistant"
