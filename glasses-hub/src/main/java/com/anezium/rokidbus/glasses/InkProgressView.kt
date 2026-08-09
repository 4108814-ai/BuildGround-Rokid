package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.anezium.rokidbus.ink.RenderNode
import kotlin.math.roundToInt

internal class InkProgressView(
    context: Context,
    private val palette: InkColorPalette,
) : View(context), InkAnimatedLeaf {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var percent = 0f
    private var targetPercent = 0f
    private var showInfo = false
    private var strokeWidthPx = resources.displayMetrics.density * 6f
    private val motion = HudMotionValue(0f) {
        percent = it
        invalidate()
    }

    fun updateNode(node: RenderNode) {
        val next = InkProgressLogic.normalizePercent(node.attributes["percent"])
        showInfo = node.attributes["show-info"] == true
        strokeWidthPx = ((node.attributes["stroke-width"] as? Number)?.toFloat() ?: 6f) * resources.displayMetrics.density
        val active = node.attributes["active"] == true
        val duration = ((node.attributes["duration"] as? Number)?.toLong() ?: HudMotion.STANDARD_MS)
            .coerceIn(0L, 5_000L)
        targetPercent = next
        if (active) motion.animateTo(next, duration, HudMotion.enter) else motion.snapTo(next)
        contentDescription = "Progress ${next.roundToInt()} percent"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = InkProgressLogic.geometry(percent, width.toFloat(), height.toFloat(), strokeWidthPx, showInfo)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = geometry.strokeWidth
        paint.color = palette.dim
        canvas.drawLine(geometry.trackStart, geometry.centerY, geometry.trackEnd, geometry.centerY, paint)
        paint.color = palette.phosphor
        canvas.drawLine(geometry.trackStart, geometry.centerY, geometry.fillEnd, geometry.centerY, paint)
        if (showInfo) {
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.RIGHT
            val baseline = geometry.centerY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText("${percent.roundToInt()}%", width.toFloat(), baseline, paint)
        }
    }

    override fun onInkVisibilityChanged(visible: Boolean) {
        if (!visible) motion.cancel()
    }

    override fun cancelInkAnimation() {
        motion.cancel()
    }

    override fun onDetachedFromWindow() {
        cancelInkAnimation()
        super.onDetachedFromWindow()
    }
}
