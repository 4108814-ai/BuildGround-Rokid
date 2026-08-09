package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeMorphLogicTest {
    @Test
    fun `assistant notice and ink surface require the same owner`() {
        assertTrue(noticeMatchesInkOwner("assistant", "assistant"))
        assertFalse(noticeMatchesInkOwner("relay", "assistant"))
        assertFalse(noticeMatchesInkOwner("assistant", "relay"))
        assertFalse(noticeMatchesInkOwner("relay", "relay"))
    }

    @Test
    fun `morph is skipped when there is no notice`() {
        assertFalse(noticeMatchesInkOwner(null, "assistant"))
        assertFalse(noticeMatchesInkOwner(null, ""))
    }

    @Test
    fun `one progress value drives only transform and crossfade`() {
        val inkLayout = HudBounds(left = 18, top = 24, right = 462, bottom = 612)
        val start = HudBounds(left = 20, top = 12, right = 460, bottom = 92)
        val target = HudBounds(left = 28, top = 40, right = 452, bottom = 260)

        val startFrame = requireNotNull(
            noticeMorphFrame(inkLayout, start, target, progress = 0f, initialNoticeAlpha = 0.8f),
        )
        val middleFrame = requireNotNull(
            noticeMorphFrame(inkLayout, start, target, progress = 0.5f, initialNoticeAlpha = 0.8f),
        )
        val endFrame = requireNotNull(
            noticeMorphFrame(inkLayout, start, target, progress = 1f, initialNoticeAlpha = 0.8f),
        )

        assertEquals(inkLayout, startFrame.layoutBounds)
        assertEquals(inkLayout, middleFrame.layoutBounds)
        assertEquals(inkLayout, endFrame.layoutBounds)
        assertEquals(start, startFrame.visualContentBounds(target))
        assertEquals(HudBounds(24, 26, 456, 176), middleFrame.visualContentBounds(target))
        assertEquals(target, endFrame.visualContentBounds(target))
        assertEquals(0.8f, startFrame.noticeAlpha, 0f)
        assertEquals(0f, startFrame.inkAlpha, 0f)
        assertEquals(0.4f, middleFrame.noticeAlpha, 0f)
        assertEquals(0.5f, middleFrame.inkAlpha, 0f)
        assertEquals(0f, endFrame.noticeAlpha, 0f)
        assertEquals(1f, endFrame.inkAlpha, 0f)
    }

    @Test
    fun `geometry converts screen bounds into fixed host coordinates and clamps progress`() {
        val screen = HudBounds(left = 24, top = 40, right = 224, bottom = 140)
        assertEquals(
            HudBounds(left = 20, top = 32, right = 220, bottom = 132),
            screen.relativeTo(originX = 4, originY = 8),
        )
        val layout = HudBounds(0, 0, 480, 640)
        val target = HudBounds(0, 0, 1, 1)
        assertEquals(screen, requireNotNull(noticeMorphFrame(layout, screen, target, -2f)).visualContentBounds(target))
        assertEquals(target, requireNotNull(noticeMorphFrame(layout, screen, target, 2f)).visualContentBounds(target))
        assertNull(noticeMorphFrame(layout, HudBounds(0, 0, 0, 1), target, 0f))
    }

    @Test
    fun `notice cannot complete before the ink first-frame signal`() {
        val lifecycle = NoticeMorphLifecycle()

        assertFalse(lifecycle.onAnimationComplete())
        assertEquals(NoticeMorphPhase.WAITING_FOR_FIRST_FRAME, lifecycle.phase)
        assertTrue(lifecycle.onFirstFrame())
        assertEquals(NoticeMorphPhase.ANIMATING, lifecycle.phase)
        assertTrue(lifecycle.onAnimationComplete())
        assertEquals(NoticeMorphPhase.COMPLETE, lifecycle.phase)
        assertFalse(lifecycle.onFirstFrame())
    }
}
