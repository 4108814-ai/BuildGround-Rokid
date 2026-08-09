package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `one progress value drives bounds and the content crossfade`() {
        val start = HudBounds(left = 20, top = 12, right = 460, bottom = 92)
        val target = HudBounds(left = 18, top = 24, right = 462, bottom = 612)

        assertEquals(
            NoticeMorphFrame(start, noticeAlpha = 0.8f, inkAlpha = 0f),
            noticeMorphFrame(start, target, progress = 0f, initialNoticeAlpha = 0.8f),
        )
        assertEquals(
            NoticeMorphFrame(
                bounds = HudBounds(left = 19, top = 18, right = 461, bottom = 352),
                noticeAlpha = 0.4f,
                inkAlpha = 0.5f,
            ),
            noticeMorphFrame(start, target, progress = 0.5f, initialNoticeAlpha = 0.8f),
        )
        assertEquals(
            NoticeMorphFrame(target, noticeAlpha = 0f, inkAlpha = 1f),
            noticeMorphFrame(start, target, progress = 1f, initialNoticeAlpha = 0.8f),
        )
    }

    @Test
    fun `geometry converts screen bounds into fixed host coordinates and clamps progress`() {
        val screen = HudBounds(left = 24, top = 40, right = 224, bottom = 140)
        assertEquals(
            HudBounds(left = 20, top = 32, right = 220, bottom = 132),
            screen.relativeTo(originX = 4, originY = 8),
        )
        assertEquals(
            screen,
            noticeMorphFrame(screen, HudBounds(0, 0, 1, 1), progress = -2f).bounds,
        )
        assertEquals(
            HudBounds(0, 0, 1, 1),
            noticeMorphFrame(screen, HudBounds(0, 0, 1, 1), progress = 2f).bounds,
        )
    }
}
