package com.anezium.rokidbus.glasses

internal enum class InkLayoutSettleAction {
    WAIT_FOR_BOUNDS,
    REAPPLY_GEOMETRY,
    NONE,
}

/**
 * Bounds become trustworthy only after the projected tree has completed one
 * layout, then one clean layout with geometry resolved against those bounds.
 */
internal class InkLayoutSettlePolicy {
    private data class Key(
        val projection: Long,
        val width: Int,
        val height: Int,
    )

    private var projection = 0L
    private var geometryApplied: Key? = null
    private var settled: Key? = null

    fun onProjectionChanged() {
        projection += 1L
        geometryApplied = null
        settled = null
    }

    fun onPostLayout(width: Int, height: Int): InkLayoutSettleAction {
        if (width <= 0 || height <= 0) {
            geometryApplied = null
            settled = null
            return InkLayoutSettleAction.WAIT_FOR_BOUNDS
        }
        val key = Key(projection, width, height)
        if (settled == key) return InkLayoutSettleAction.NONE
        if (geometryApplied == key) {
            geometryApplied = null
            settled = key
            return InkLayoutSettleAction.NONE
        }
        settled = null
        return InkLayoutSettleAction.REAPPLY_GEOMETRY
    }

    fun onGeometryApplied(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        geometryApplied = Key(projection, width, height)
        settled = null
        return true
    }

    fun canDraw(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && settled == Key(projection, width, height)
}
