package com.anezium.rokidbus.glasses

internal data class SurfaceListWindow(
    val firstRow: Int,
    val lastRowExclusive: Int,
    val hiddenAbove: Int,
    val hiddenBelow: Int,
)

/**
 * Chooses a measured row window without carrying scroll state between surfaces.
 * A following row is preferred when it fits, then the earliest fitting start
 * wins so the selection moves only as far from the top as its context requires.
 */
internal fun surfaceListViewport(
    rowOuterHeightsPx: List<Int>,
    viewportHeightPx: Int,
    selectedIndex: Int?,
    indicatorHeightPx: Int,
): SurfaceListWindow {
    require(rowOuterHeightsPx.all { it >= 0 })
    require(viewportHeightPx >= 0)
    require(indicatorHeightPx >= 0)

    val rowCount = rowOuterHeightsPx.size
    if (rowCount == 0) return SurfaceListWindow(0, 0, 0, 0)

    val prefixHeights = LongArray(rowCount + 1)
    rowOuterHeightsPx.forEachIndexed { index, height ->
        prefixHeights[index + 1] = prefixHeights[index] + height
    }
    if (prefixHeights[rowCount] <= viewportHeightPx.toLong()) {
        return SurfaceListWindow(0, rowCount, 0, 0)
    }

    fun fits(firstRow: Int, lastRowExclusive: Int): Boolean {
        val rowHeight = prefixHeights[lastRowExclusive] - prefixHeights[firstRow]
        val indicatorHeight = indicatorHeightPx.toLong() *
            ((if (firstRow > 0) 1 else 0) + (if (lastRowExclusive < rowCount) 1 else 0))
        return rowHeight + indicatorHeight <= viewportHeightPx.toLong()
    }

    val selected = selectedIndex?.takeIf { it in rowOuterHeightsPx.indices }
    if (selected == null) {
        val lastRowExclusive = (1..rowCount)
            .takeWhile { fits(0, it) }
            .lastOrNull()
            ?: 1
        return window(
            firstRow = 0,
            lastRowExclusive = lastRowExclusive,
            rowCount = rowCount,
        )
    }

    val fitting = buildList {
        for (firstRow in 0..selected) {
            for (lastRowExclusive in (selected + 1)..rowCount) {
                if (fits(firstRow, lastRowExclusive)) {
                    add(firstRow to lastRowExclusive)
                }
            }
        }
    }
    if (fitting.isEmpty()) {
        return window(selected, selected + 1, rowCount)
    }

    val withFollowingContext = if (selected < rowCount - 1) {
        fitting.filter { (_, lastRowExclusive) -> lastRowExclusive >= selected + 2 }
    } else {
        fitting
    }
    val candidates = withFollowingContext.ifEmpty { fitting }
    val firstRow = candidates.minOf { (candidateFirst, _) -> candidateFirst }
    val lastRowExclusive = candidates
        .asSequence()
        .filter { (candidateFirst, _) -> candidateFirst == firstRow }
        .maxOf { (_, candidateLast) -> candidateLast }
    return window(firstRow, lastRowExclusive, rowCount)
}

private fun window(
    firstRow: Int,
    lastRowExclusive: Int,
    rowCount: Int,
): SurfaceListWindow = SurfaceListWindow(
    firstRow = firstRow,
    lastRowExclusive = lastRowExclusive,
    hiddenAbove = firstRow,
    hiddenBelow = rowCount - lastRowExclusive,
)
