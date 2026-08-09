package com.anezium.rokidbus.glasses

internal data class InkProgressGeometry(
    val trackStart: Float,
    val trackEnd: Float,
    val fillEnd: Float,
    val centerY: Float,
    val strokeWidth: Float,
)

internal object InkProgressLogic {
    fun normalizePercent(value: Any?): Float = ((value as? Number)?.toFloat() ?: 0f).coerceIn(0f, 100f)

    fun geometry(percent: Float, width: Float, height: Float, strokeWidth: Float, showInfo: Boolean): InkProgressGeometry {
        val reservedLabel = if (showInfo) 48f.coerceAtMost(width * 0.28f) else 0f
        val inset = strokeWidth / 2f
        val start = inset
        val end = (width - reservedLabel - inset).coerceAtLeast(start)
        return InkProgressGeometry(
            trackStart = start,
            trackEnd = end,
            fillEnd = start + (end - start) * percent.coerceIn(0f, 100f) / 100f,
            centerY = height / 2f,
            strokeWidth = strokeWidth.coerceIn(1f, height.coerceAtLeast(1f)),
        )
    }
}
