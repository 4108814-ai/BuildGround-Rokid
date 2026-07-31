package com.anezium.rokidbus.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRuntimeTest {
    @Test
    fun needsPlaybackAnchorUpdate_resyncsAtEachLineBoundary() {
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 3,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = 0L,
            )
        )
    }

    @Test
    fun needsPlaybackAnchorUpdate_skipsStablePlaybackWithinDriftTolerance() {
        assertFalse(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = 1_499L,
            )
        )
    }

    @Test
    fun needsPlaybackAnchorUpdate_keepsPlaybackAndSeekResyncs() {
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = false,
                positionDriftMs = 0L,
            )
        )
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = -1_500L,
            )
        )
    }
}
