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
    private var awaitingCleanLayout: Key? = null
    private var settled: Key? = null

    fun onProjectionChanged() {
        projection += 1L
        awaitingCleanLayout = null
        settled = null
    }

    fun onPostLayout(width: Int, height: Int): InkLayoutSettleAction {
        if (width <= 0 || height <= 0) {
            awaitingCleanLayout = null
            settled = null
            return InkLayoutSettleAction.WAIT_FOR_BOUNDS
        }
        val key = Key(projection, width, height)
        if (settled == key) return InkLayoutSettleAction.NONE
        if (awaitingCleanLayout == key) {
            awaitingCleanLayout = null
            settled = key
            return InkLayoutSettleAction.NONE
        }
        awaitingCleanLayout = key
        settled = null
        return InkLayoutSettleAction.REAPPLY_GEOMETRY
    }

    fun canDraw(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && settled == Key(projection, width, height)
}
