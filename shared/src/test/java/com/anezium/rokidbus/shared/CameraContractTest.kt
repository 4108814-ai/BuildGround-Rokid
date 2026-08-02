package com.anezium.rokidbus.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraContractTest {
    @Test
    fun `all camera contract paths are protected with segment boundaries`() {
        listOf(
            BusPaths.CAMERA_SESSION_STATE,
            BusPaths.CAMERA_LINK_OFFER,
            BusPaths.CAMERA_FREEZE_RESULT,
            BusPaths.CAMERA_OVERLAY,
            BusPaths.CAMERA_SNAPSHOT_REQUEST,
            BusPaths.CAMERA_SNAPSHOT_RESULT,
            BusPaths.CAMERA_SNAPSHOT_ERROR,
        ).forEach { path ->
            assertTrue(path, BusPaths.isProtectedCameraPath(path))
            assertTrue("$path/future", BusPaths.isProtectedCameraPath("$path/future"))
        }
        assertFalse(BusPaths.isProtectedCameraPath("/camera/session/stateful"))
        assertFalse(BusPaths.isProtectedCameraPath("/plugin/lens"))
    }

    @Test
    fun `camera readiness has an independent feature bit`() {
        assertTrue(BusCapabilityBits.CAMERA_CONSUMER_READY != BusCapabilityBits.IMAGE_SURFACE)
    }
}
