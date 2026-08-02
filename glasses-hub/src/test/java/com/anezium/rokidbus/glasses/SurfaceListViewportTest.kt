package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceListViewportTest {

    @Test
    fun `all rows fit with no indicators and the full range`() {
        assertEquals(
            SurfaceListWindow(0, 3, 0, 0),
            viewport(heights = listOf(20, 31, 15), height = 66, selected = 1),
        )
    }

    @Test
    fun `overflow without selection stays at the top with a bottom indicator`() {
        assertEquals(
            SurfaceListWindow(0, 3, 0, 2),
            viewport(heights = List(5) { 20 }, height = 70, selected = null),
        )
    }

    @Test
    fun `selection at the top keeps the top anchored`() {
        assertEquals(
            SurfaceListWindow(0, 3, 0, 3),
            viewport(heights = List(6) { 20 }, height = 80, selected = 0),
        )
    }

    @Test
    fun `middle selection keeps one following row visible`() {
        assertEquals(
            SurfaceListWindow(2, 5, 2, 1),
            viewport(heights = List(6) { 20 }, height = 80, selected = 3),
        )
    }

    @Test
    fun `selection on the last row ends the window there`() {
        assertEquals(
            SurfaceListWindow(3, 6, 3, 0),
            viewport(heights = List(6) { 20 }, height = 80, selected = 5),
        )
    }

    @Test
    fun `wrap from last back to first derives a fresh top window`() {
        val heights = List(6) { 20 }

        assertEquals(
            SurfaceListWindow(3, 6, 3, 0),
            viewport(heights, height = 80, selected = 5),
        )
        assertEquals(
            SurfaceListWindow(0, 3, 0, 3),
            viewport(heights, height = 80, selected = 0),
        )
    }

    @Test
    fun `bottom indicator displaces a row that otherwise fits`() {
        assertEquals(
            SurfaceListWindow(0, 2, 0, 2),
            viewport(heights = List(4) { 20 }, height = 60, selected = null),
        )
    }

    @Test
    fun `a selected row taller than the viewport still starts at its top`() {
        assertEquals(
            SurfaceListWindow(0, 1, 0, 0),
            viewport(heights = listOf(100), height = 60, selected = 0),
        )
    }

    private fun viewport(
        heights: List<Int>,
        height: Int,
        selected: Int?,
    ): SurfaceListWindow = surfaceListViewport(
        rowOuterHeightsPx = heights,
        viewportHeightPx = height,
        selectedIndex = selected,
        indicatorHeightPx = 10,
    )
}
