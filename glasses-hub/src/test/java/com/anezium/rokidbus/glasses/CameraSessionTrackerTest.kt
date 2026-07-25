package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionTrackerTest {
    @Test
    fun `open and close publish exactly one edge each`() {
        val published = mutableListOf<Boolean>()
        val tracker = CameraSessionTracker { published += it }

        tracker.onSessionState("s1", "opened")
        tracker.onSessionState("s1", "opened")
        assertTrue(tracker.isActive())
        tracker.onSessionState("s1", "closed")
        tracker.onSessionState("s1", "closed")

        assertEquals(listOf(true, false), published)
        assertFalse(tracker.isActive())
    }

    @Test
    fun `a close for a stale session id does not release the live one`() {
        val published = mutableListOf<Boolean>()
        val tracker = CameraSessionTracker { published += it }

        tracker.onSessionState("s1", "opened")
        tracker.onSessionState("s0", "closed")

        assertTrue(tracker.isActive())
        assertEquals(listOf(true), published)
    }

    @Test
    fun `a new session id while one is open keeps the tracker active without a blip`() {
        val published = mutableListOf<Boolean>()
        val tracker = CameraSessionTracker { published += it }

        tracker.onSessionState("s1", "opened")
        tracker.onSessionState("s2", "opened")
        tracker.onSessionState("s2", "closed")

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `blank ids and unknown states are ignored`() {
        val published = mutableListOf<Boolean>()
        val tracker = CameraSessionTracker { published += it }

        assertFalse(tracker.onSessionState("", "opened"))
        assertFalse(tracker.onSessionState("s1", "paused"))
        assertFalse(tracker.isActive())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `reset releases an active session exactly once`() {
        val published = mutableListOf<Boolean>()
        val tracker = CameraSessionTracker { published += it }

        tracker.onSessionState("s1", "opened")
        assertTrue(tracker.reset())
        assertFalse(tracker.reset())

        assertEquals(listOf(true, false), published)
    }

    @Test
    fun `a camera-blocked sync recovers when the camera process is gone`() {
        assertTrue(
            CameraSessionLivenessPolicy.shouldResetTracker(
                MediaSyncSkipReason.CAMERA_ACTIVE,
                cameraProcessAlive = false,
            ),
        )
    }

    @Test
    fun `a live camera process keeps its session`() {
        assertFalse(
            CameraSessionLivenessPolicy.shouldResetTracker(
                MediaSyncSkipReason.CAMERA_ACTIVE,
                cameraProcessAlive = true,
            ),
        )
    }

    @Test
    fun `an unreadable process list never cancels a session`() {
        assertFalse(
            CameraSessionLivenessPolicy.shouldResetTracker(
                MediaSyncSkipReason.CAMERA_ACTIVE,
                cameraProcessAlive = null,
            ),
        )
    }

    @Test
    fun `other skip reasons never touch the tracker`() {
        MediaSyncSkipReason.entries
            .filter { it != MediaSyncSkipReason.CAMERA_ACTIVE }
            .forEach { reason ->
                assertFalse(
                    CameraSessionLivenessPolicy.shouldResetTracker(reason, cameraProcessAlive = false),
                )
            }
    }
}
