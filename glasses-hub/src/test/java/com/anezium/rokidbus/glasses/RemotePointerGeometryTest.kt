package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePointerGeometryTest {
    @Test
    fun `center maps to the measured display center`() {
        assertEquals(
            GlassesPointerPixel(240f, 320f),
            RemotePointerGeometry.toPixels(
                GlassesPointerPosition(0.5, 0.5),
                widthPixels = 480,
                heightPixels = 640,
                radiusPixels = 12f,
            ),
        )
    }

    @Test
    fun `cursor radius clamps every screen edge`() {
        assertEquals(
            GlassesPointerPixel(12f, 628f),
            RemotePointerGeometry.toPixels(
                GlassesPointerPosition(-1.0, 2.0),
                widthPixels = 480,
                heightPixels = 640,
                radiusPixels = 12f,
            ),
        )
        assertEquals(
            GlassesPointerPixel(468f, 12f),
            RemotePointerGeometry.toPixels(
                GlassesPointerPosition(1.0, 0.0),
                widthPixels = 480,
                heightPixels = 640,
                radiusPixels = 12f,
            ),
        )
    }

    @Test
    fun `invalid viewport position and radius fail closed`() {
        assertNull(RemotePointerGeometry.toPixels(GlassesPointerPosition(0.5, 0.5), 0, 640, 12f))
        assertNull(RemotePointerGeometry.toPixels(GlassesPointerPosition(0.5, 0.5), 480, -1, 12f))
        assertNull(RemotePointerGeometry.toPixels(GlassesPointerPosition(Double.NaN, 0.5), 480, 640, 12f))
        assertNull(RemotePointerGeometry.toPixels(GlassesPointerPosition(0.5, 0.5), 480, 640, -1f))
    }

    @Test
    fun `oversized radius collapses safely to the display center`() {
        assertEquals(
            GlassesPointerPixel(10f, 5f),
            RemotePointerGeometry.toPixels(
                GlassesPointerPosition(0.0, 1.0),
                widthPixels = 20,
                heightPixels = 10,
                radiusPixels = 50f,
            ),
        )
    }
}
