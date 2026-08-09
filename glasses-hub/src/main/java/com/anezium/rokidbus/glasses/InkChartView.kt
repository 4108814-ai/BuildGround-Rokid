package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.anezium.rokidbus.ink.RenderNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class InkChartView(
    context: Context,
    private val palette: InkColorPalette,
) : View(context), InkAnimatedLeaf {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var target = InkChartLogic.parse(emptyMap())
    private var start = target
    private var displayed = target
    private val progress = HudMotionValue(1f) { amount ->
        displayed = InkChartLogic.interpolate(start, target, amount)
        invalidate()
    }

    init {
        contentDescription = "Ink chart"
    }

    fun updateNode(node: RenderNode) {
        val next = InkChartLogic.parse(node.attributes)
        if (next == target) return
        start = displayed
        target = next
        progress.snapTo(0f)
        if (next.animate) {
            progress.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)
        } else {
            progress.snapTo(1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = InkChartLogic.geometry(displayed, width.toFloat(), height.toFloat())
        drawFrame(canvas, geometry)
        when (displayed.type) {
            InkChartType.PIE -> drawPie(canvas, geometry)
            InkChartType.RADAR -> drawRadar(canvas, geometry)
            InkChartType.BAR -> drawBars(canvas, geometry)
            InkChartType.LINE,
            InkChartType.AREA,
            -> drawCartesian(canvas, geometry)
        }
        drawTextMarks(canvas, geometry)
    }

    override fun onInkVisibilityChanged(visible: Boolean) {
        if (!visible) progress.cancel()
    }

    override fun cancelInkAnimation() {
        progress.cancel()
    }

    override fun onDetachedFromWindow() {
        cancelInkAnimation()
        super.onDetachedFromWindow()
    }

    private fun drawFrame(canvas: Canvas, geometry: InkChartGeometry) {
        paint.resetStroke(palette.dim, 1f)
        canvas.drawLine(geometry.plotLeft, geometry.plotBottom, geometry.plotRight, geometry.plotBottom, paint)
        canvas.drawLine(geometry.plotLeft, geometry.plotTop, geometry.plotLeft, geometry.plotBottom, paint)
    }

    private fun drawTextMarks(canvas: Canvas, geometry: InkChartGeometry) {
        if (geometry.valueLabels.isEmpty() && geometry.axisLabels.isEmpty()) return
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = (height * 0.11f).coerceIn(12f, 22f)
        }
        text.color = palette.text
        geometry.valueLabels.forEach { mark -> canvas.drawText(mark.text, mark.x, mark.y, text) }
        text.color = palette.muted
        text.textSize = (height * 0.095f).coerceIn(11f, 19f)
        geometry.axisLabels.forEach { mark -> canvas.drawText(mark.text, mark.x, mark.y, text) }
    }

    private fun drawCartesian(canvas: Canvas, geometry: InkChartGeometry) {
        geometry.series.forEach { series ->
            if (series.points.isEmpty()) return@forEach
            configureSeries(series)
            buildLinePath(series.points, displayed.series.getOrNull(geometry.series.indexOf(series))?.smooth == true)
            if (displayed.type == InkChartType.AREA) {
                val fill = Paint(paint).apply {
                    style = Paint.Style.FILL
                    color = palette.dim
                    pathEffect = null
                }
                val area = Path(path).apply {
                    lineTo(series.points.last().x, geometry.plotBottom)
                    lineTo(series.points.first().x, geometry.plotBottom)
                    close()
                }
                canvas.drawPath(area, fill)
            }
            canvas.drawPath(path, paint)
            series.points.forEach { drawMarker(canvas, it, series.marker, series.strokeWidth + 2f) }
            series.averageY?.let { y ->
                paint.resetStroke(palette.muted, 1f)
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
                canvas.drawLine(geometry.plotLeft, y, geometry.plotRight, y, paint)
            }
        }
    }

    private fun drawBars(canvas: Canvas, geometry: InkChartGeometry) {
        val count = geometry.series.maxOfOrNull { it.points.size }?.coerceAtLeast(1) ?: 1
        val seriesCount = geometry.series.size.coerceAtLeast(1)
        val slot = (geometry.plotRight - geometry.plotLeft) / count
        geometry.series.forEachIndexed { seriesIndex, series ->
            configureSeries(series)
            val barWidth = slot * 0.72f / seriesCount
            series.points.forEachIndexed { index, point ->
                val left = geometry.plotLeft + index * slot + seriesIndex * barWidth + slot * 0.14f
                val baseline = geometry.plotBottom
                paint.style = Paint.Style.STROKE
                canvas.drawRect(left, min(point.y, baseline), left + barWidth, baseline, paint)
                drawMarker(canvas, InkChartCoordinate(left + barWidth / 2f, point.y), series.marker, series.strokeWidth + 2f)
            }
        }
    }

    private fun drawPie(canvas: Canvas, geometry: InkChartGeometry) {
        val radius = min(width, height) * 0.38f
        val oval = RectF(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius)
        geometry.slices.forEach { slice ->
            paint.resetStroke(palette.phosphor, slice.strokeWidth)
            paint.pathEffect = slice.dash.takeIf(List<Float>::isNotEmpty)?.toFloatArray()?.let { DashPathEffect(it, 0f) }
            canvas.drawArc(oval, slice.startDegrees, slice.sweepDegrees, true, paint)
            val angle = (slice.startDegrees + slice.sweepDegrees / 2f) * PI / 180.0
            drawMarker(
                canvas,
                InkChartCoordinate(
                    (width / 2f + cos(angle) * radius * 0.72f).toFloat(),
                    (height / 2f + sin(angle) * radius * 0.72f).toFloat(),
                ),
                slice.marker,
                slice.strokeWidth + 2f,
            )
        }
    }

    private fun drawRadar(canvas: Canvas, geometry: InkChartGeometry) {
        paint.resetStroke(palette.dim, 1f)
        geometry.radarAxes.forEach { (start, end) -> canvas.drawLine(start.x, start.y, end.x, end.y, paint) }
        geometry.series.forEach { series ->
            if (series.points.size < 3) return@forEach
            configureSeries(series)
            path.reset()
            path.moveTo(series.points.first().x, series.points.first().y)
            series.points.drop(1).forEach { path.lineTo(it.x, it.y) }
            path.close()
            canvas.drawPath(path, paint)
            series.points.forEach { drawMarker(canvas, it, series.marker, series.strokeWidth + 2f) }
        }
    }

    private fun configureSeries(series: InkChartSeriesGeometry) {
        paint.resetStroke(palette.phosphor, series.strokeWidth)
        paint.pathEffect = series.dash.takeIf(List<Float>::isNotEmpty)?.toFloatArray()?.let { DashPathEffect(it, 0f) }
    }

    private fun buildLinePath(points: List<InkChartCoordinate>, smooth: Boolean) {
        path.reset()
        path.moveTo(points.first().x, points.first().y)
        if (!smooth || points.size < 3) {
            points.drop(1).forEach { path.lineTo(it.x, it.y) }
            return
        }
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val midpoint = (previous.x + current.x) / 2f
            path.cubicTo(midpoint, previous.y, midpoint, current.y, current.x, current.y)
        }
    }

    private fun drawMarker(canvas: Canvas, point: InkChartCoordinate, marker: InkChartMarker, size: Float) {
        paint.pathEffect = null
        paint.style = Paint.Style.STROKE
        when (marker) {
            InkChartMarker.CIRCLE -> canvas.drawCircle(point.x, point.y, size, paint)
            InkChartMarker.SQUARE -> canvas.drawRect(point.x - size, point.y - size, point.x + size, point.y + size, paint)
            InkChartMarker.DIAMOND -> {
                path.reset()
                path.moveTo(point.x, point.y - size)
                path.lineTo(point.x + size, point.y)
                path.lineTo(point.x, point.y + size)
                path.lineTo(point.x - size, point.y)
                path.close()
                canvas.drawPath(path, paint)
            }
            InkChartMarker.TRIANGLE -> {
                path.reset()
                path.moveTo(point.x, point.y - size)
                path.lineTo(point.x + size, point.y + size)
                path.lineTo(point.x - size, point.y + size)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun Paint.resetStroke(color: Int, width: Float) {
        reset()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
}
