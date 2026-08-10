package com.anezium.rokidbus.glasses

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class InkChartType { LINE, AREA, PIE, RADAR, BAR }
internal enum class InkChartMarker { CIRCLE, SQUARE, DIAMOND, TRIANGLE }

internal data class InkChartPoint(val x: Double, val y: Double, val label: String = "")

internal data class InkChartSeries(
    val points: List<InkChartPoint>,
    val smooth: Boolean,
    val requestedWidth: Float? = null,
)

internal data class InkChartModel(
    val type: InkChartType,
    val series: List<InkChartSeries>,
    val minimumX: Double,
    val maximumX: Double,
    val minimumY: Double,
    val maximumY: Double,
    val animate: Boolean,
    val showAverage: Boolean,
    val horizontalBars: Boolean,
    val showValueLabels: Boolean = false,
)

internal data class InkChartCoordinate(val x: Float, val y: Float)

internal data class InkChartSeriesGeometry(
    val points: List<InkChartCoordinate>,
    val dash: List<Float>,
    val marker: InkChartMarker,
    val strokeWidth: Float,
    val averageY: Float? = null,
)

internal data class InkChartSliceGeometry(
    val startDegrees: Float,
    val sweepDegrees: Float,
    val marker: InkChartMarker,
    val dash: List<Float>,
    val strokeWidth: Float,
)

internal data class InkChartTextMark(val x: Float, val y: Float, val text: String)

internal data class InkChartGeometry(
    val series: List<InkChartSeriesGeometry> = emptyList(),
    val slices: List<InkChartSliceGeometry> = emptyList(),
    val radarAxes: List<Pair<InkChartCoordinate, InkChartCoordinate>> = emptyList(),
    val valueLabels: List<InkChartTextMark> = emptyList(),
    val axisLabels: List<InkChartTextMark> = emptyList(),
    val plotLeft: Float = 0f,
    val plotRight: Float = 0f,
    val plotTop: Float = 0f,
    val plotBottom: Float = 0f,
)

internal object InkChartLogic {
    fun parse(attributes: Map<String, Any?>): InkChartModel {
        val type = when (attributes["type"]?.toString()?.lowercase()) {
            "area" -> InkChartType.AREA
            "pie" -> InkChartType.PIE
            "radar" -> InkChartType.RADAR
            "bar" -> InkChartType.BAR
            else -> InkChartType.LINE
        }
        val sharedData = attributes["data"].mapList()
        val seriesConfigs = when (val raw = attributes["series"] ?: "value") {
            is List<*> -> raw.mapNotNull { it as? Map<*, *> }
            else -> listOf(mapOf("yName" to raw.toString()))
        }
        val series = seriesConfigs.map { config ->
            val yKey = (config["yName"] ?: config["yKey"] ?: "value").toString()
            val xKey = (config["xName"] ?: config["xKey"])?.toString()
            val source = (config["dataSource"] as? List<*>) ?: sharedData
            InkChartSeries(
                points = source.mapIndexedNotNull { index, rawPoint ->
                    val point = rawPoint as? Map<*, *> ?: return@mapIndexedNotNull null
                    val y = (point[yKey] as? Number)?.toDouble() ?: return@mapIndexedNotNull null
                    val x = (xKey?.let(point::get) as? Number)?.toDouble() ?: index.toDouble()
                    val label = point["label"]?.toString()
                        ?: point.entries.firstOrNull { it.value is String }?.value?.toString().orEmpty()
                    InkChartPoint(x, y, label)
                },
                smooth = (config["smooth"] as? Boolean) ?: (attributes["smooth"] as? Boolean) ?: true,
                requestedWidth = (config["width"] as? Number)?.toFloat(),
            )
        }.ifEmpty { listOf(InkChartSeries(emptyList(), smooth = true)) }

        val xAxis = (attributes["x-axis"] ?: attributes["xAxis"]) as? Map<*, *>
        val yAxis = (attributes["y-axis"] ?: attributes["yAxis"]) as? Map<*, *>
        val allPoints = series.flatMap(InkChartSeries::points)
        val naturalMinX = allPoints.minOfOrNull(InkChartPoint::x) ?: 0.0
        val naturalMaxX = allPoints.maxOfOrNull(InkChartPoint::x) ?: 1.0
        val naturalMinY = allPoints.minOfOrNull(InkChartPoint::y) ?: 0.0
        val naturalMaxY = allPoints.maxOfOrNull(InkChartPoint::y) ?: 1.0
        val includeZero = type in setOf(InkChartType.AREA, InkChartType.BAR, InkChartType.PIE, InkChartType.RADAR)
        val minimumX = axisNumber(xAxis, "minimum", "min") ?: naturalMinX
        val maximumX = axisNumber(xAxis, "maximum", "max") ?: naturalMaxX
        val minimumY = axisNumber(yAxis, "minimum", "min") ?: if (includeZero) min(0.0, naturalMinY) else naturalMinY
        val maximumY = axisNumber(yAxis, "maximum", "max") ?: max(if (includeZero) 0.0 else naturalMaxY, naturalMaxY)
        return InkChartModel(
            type = type,
            series = series,
            minimumX = minimumX,
            maximumX = if (maximumX > minimumX) maximumX else minimumX + 1.0,
            minimumY = minimumY,
            maximumY = if (maximumY > minimumY) maximumY else minimumY + 1.0,
            animate = attributes["animate"] == true,
            showAverage = attributes["show-average"] == true || attributes["showAverage"] == true,
            horizontalBars = attributes["direction"]?.toString()?.equals("horizontal", true) == true,
            showValueLabels = attributes["show-value-labels"] == true,
        )
    }

    fun geometry(model: InkChartModel, width: Float, height: Float): InkChartGeometry {
        val first = model.series.firstOrNull()
        val hasAxisLabels = model.type in setOf(InkChartType.LINE, InkChartType.AREA, InkChartType.BAR) &&
            first?.points?.any { it.label.isNotBlank() } == true
        val hasValueLabels = model.showValueLabels &&
            model.type in setOf(InkChartType.LINE, InkChartType.AREA, InkChartType.BAR)
        val left = width * 0.08f
        val right = width * 0.96f
        val top = if (hasValueLabels) height * 0.18f else height * 0.08f
        val bottom = if (hasAxisLabels) height * 0.78f else height * 0.9f
        return when (model.type) {
            InkChartType.PIE -> pieGeometry(model).copy(
                plotLeft = left,
                plotRight = right,
                plotTop = top,
                plotBottom = bottom,
            )
            InkChartType.RADAR -> radarGeometry(model, left, top, right, bottom).copy(
                plotLeft = left,
                plotRight = right,
                plotTop = top,
                plotBottom = bottom,
            )
            else -> InkChartGeometry(
                plotLeft = left,
                plotRight = right,
                plotTop = top,
                plotBottom = bottom,
                series = model.series.mapIndexed { index, series ->
                    val visual = visual(index, series.requestedWidth)
                    val points = series.points.map { point ->
                        InkChartCoordinate(
                            x = map(point.x, model.minimumX, model.maximumX, left, right),
                            y = map(point.y, model.minimumY, model.maximumY, bottom, top),
                        )
                    }
                    InkChartSeriesGeometry(
                        points = points,
                        dash = visual.first,
                        marker = visual.second,
                        strokeWidth = visual.third,
                        averageY = if (model.showAverage && model.series.size == 1 && points.isNotEmpty()) {
                            val average = series.points.map(InkChartPoint::y).average()
                            map(average, model.minimumY, model.maximumY, bottom, top)
                        } else {
                            null
                        },
                    )
                },
                // Labels come from the first series only: on monochrome optics,
                // stacked per-series numbers would be indistinguishable noise.
                valueLabels = if (hasValueLabels && first != null) {
                    strided(first.points).map { point ->
                        InkChartTextMark(
                            x = map(point.x, model.minimumX, model.maximumX, left, right),
                            y = map(point.y, model.minimumY, model.maximumY, bottom, top) - height * 0.07f,
                            text = compactNumber(point.y),
                        )
                    }
                } else {
                    emptyList()
                },
                axisLabels = first
                    ?.takeIf { hasAxisLabels }
                    ?.let { series ->
                        strided(series.points.filter { it.label.isNotBlank() }).map { point ->
                            InkChartTextMark(
                                x = map(point.x, model.minimumX, model.maximumX, left, right),
                                y = height * 0.97f,
                                text = point.label,
                            )
                        }
                    }
                    .orEmpty(),
            )
        }
    }

    private fun strided(points: List<InkChartPoint>): List<InkChartPoint> {
        if (points.size <= MAX_TEXT_MARKS) return points
        val stride = (points.size + MAX_TEXT_MARKS - 1) / MAX_TEXT_MARKS
        return points.filterIndexed { index, _ -> index % stride == 0 }
    }

    private fun compactNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

    private const val MAX_TEXT_MARKS = 8

    fun interpolate(from: InkChartModel, target: InkChartModel, progress: Float): InkChartModel {
        if (from.type != target.type) return target
        val amount = progress.coerceIn(0f, 1f).toDouble()
        val series = target.series.mapIndexed { seriesIndex, targetSeries ->
            val oldSeries = from.series.getOrNull(seriesIndex)
            targetSeries.copy(
                points = targetSeries.points.mapIndexed { pointIndex, targetPoint ->
                    val oldPoint = oldSeries?.points?.getOrNull(pointIndex) ?: targetPoint
                    targetPoint.copy(
                        x = lerp(oldPoint.x, targetPoint.x, amount),
                        y = lerp(oldPoint.y, targetPoint.y, amount),
                    )
                },
            )
        }
        return target.copy(
            series = series,
            minimumX = lerp(from.minimumX, target.minimumX, amount),
            maximumX = lerp(from.maximumX, target.maximumX, amount),
            minimumY = lerp(from.minimumY, target.minimumY, amount),
            maximumY = lerp(from.maximumY, target.maximumY, amount),
        )
    }

    private fun pieGeometry(model: InkChartModel): InkChartGeometry {
        val values = model.series.firstOrNull()?.points.orEmpty().map { it.y.coerceAtLeast(0.0) }
        val total = values.sum().takeIf { it > 0.0 } ?: 1.0
        var start = -90f
        return InkChartGeometry(
            slices = values.mapIndexed { index, value ->
                val sweep = (value / total * 360.0).toFloat()
                val visual = visual(index, null)
                InkChartSliceGeometry(start, sweep, visual.second, visual.first, visual.third).also { start += sweep }
            },
        )
    }

    private fun radarGeometry(
        model: InkChartModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): InkChartGeometry {
        val axisCount = model.series.maxOfOrNull { it.points.size } ?: 0
        if (axisCount < 3) return InkChartGeometry()
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val radius = min(right - left, bottom - top) / 2f
        fun coordinate(index: Int, ratio: Double): InkChartCoordinate {
            val angle = -PI / 2.0 + index * 2.0 * PI / axisCount
            return InkChartCoordinate(
                (centerX + cos(angle) * radius * ratio).toFloat(),
                (centerY + sin(angle) * radius * ratio).toFloat(),
            )
        }
        return InkChartGeometry(
            radarAxes = List(axisCount) { InkChartCoordinate(centerX, centerY) to coordinate(it, 1.0) },
            series = model.series.mapIndexed { index, series ->
                val visual = visual(index, series.requestedWidth)
                InkChartSeriesGeometry(
                    points = series.points.mapIndexed { pointIndex, point ->
                        coordinate(pointIndex, ((point.y - model.minimumY) / (model.maximumY - model.minimumY)).coerceIn(0.0, 1.0))
                    },
                    dash = visual.first,
                    marker = visual.second,
                    strokeWidth = visual.third,
                )
            },
        )
    }

    private fun visual(index: Int, requestedWidth: Float?): Triple<List<Float>, InkChartMarker, Float> {
        val dashes = listOf(emptyList(), listOf(12f, 6f), listOf(3f, 5f), listOf(14f, 4f, 3f, 4f))
        val markers = InkChartMarker.entries
        // The index step remains even when a page supplies width, so two series
        // never become indistinguishable on monochrome optics.
        val width = (requestedWidth ?: 2f).coerceAtLeast(1f) + index * 0.75f
        return Triple(dashes[index % dashes.size], markers[index % markers.size], width)
    }

    private fun axisNumber(axis: Map<*, *>?, primary: String, alias: String): Double? =
        (axis?.get(primary) ?: axis?.get(alias) as? Number)?.let { (it as? Number)?.toDouble() }

    private fun map(value: Double, minimum: Double, maximum: Double, low: Float, high: Float): Float =
        (low + ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0) * (high - low)).toFloat()

    private fun lerp(from: Double, target: Double, amount: Double): Double = from + (target - from) * amount

    private fun Any?.mapList(): List<*> = this as? List<*> ?: emptyList<Any?>()
}
