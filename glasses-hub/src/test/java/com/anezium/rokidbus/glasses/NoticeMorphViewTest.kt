package com.anezium.rokidbus.glasses

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NoticeMorphViewTest {
    @Test
    fun `ink keeps full final layout bounds while only its transform changes`() {
        val context = RuntimeEnvironment.getApplication()
        val ink = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(444, 588)
            layout(18, 24, 462, 612)
        }
        val fullLayout = HudBounds(ink.left, ink.top, ink.right, ink.bottom)
        val band = HudBounds(20, 12, 460, 92)
        val finalContent = HudBounds(28, 40, 452, 260)

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val frame = requireNotNull(
                noticeMorphFrame(fullLayout, band, finalContent, progress),
            )
            applyNoticeMorphTransform(ink, frame)

            assertEquals(fullLayout, HudBounds(ink.left, ink.top, ink.right, ink.bottom))
            assertEquals(444, ink.layoutParams.width)
            assertEquals(588, ink.layoutParams.height)
        }

        assertEquals(1f, ink.scaleX, 0f)
        assertEquals(1f, ink.scaleY, 0f)
        assertEquals(0f, ink.translationX, 0f)
        assertEquals(0f, ink.translationY, 0f)

        val initial = requireNotNull(noticeMorphFrame(fullLayout, band, finalContent, 0f))
        assertNotEquals(1f, initial.scaleY)
        assertNotEquals(0f, initial.translationY)
    }
}
