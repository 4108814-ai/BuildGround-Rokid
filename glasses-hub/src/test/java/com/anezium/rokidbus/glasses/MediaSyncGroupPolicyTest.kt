package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncGroupPolicyTest {
    private val expected = "DIRECT-NS-ab3d9k"

    @Test
    fun `our own group is recognised by name`() {
        assertEquals(
            MediaSyncGroupOwnership.OURS,
            MediaSyncGroupPolicy.classify(expected, expected),
        )
    }

    @Test
    fun `a media sync group from a previous profile is still ours to rebuild`() {
        assertEquals(
            MediaSyncGroupOwnership.OURS,
            MediaSyncGroupPolicy.classify("DIRECT-NS-oldkey", expected),
        )
    }

    @Test
    fun `the camera link's parked group is never ours to remove`() {
        // The camera keeps this group alive for ~40 s after a session so a warm reopen costs
        // 1.4 s instead of 5-7 s. Removing it would silently degrade the camera.
        assertEquals(
            MediaSyncGroupOwnership.CAMERA_LINK,
            MediaSyncGroupPolicy.classify("DIRECT-RN-1a2b3c", expected),
        )
    }

    @Test
    fun `anything else is a foreign group we may clear`() {
        listOf("DIRECT-xy-Android_1234", "AndroidShare_9182", "", null).forEach { name ->
            assertEquals(
                "classify($name)",
                MediaSyncGroupOwnership.FOREIGN,
                MediaSyncGroupPolicy.classify(name, expected),
            )
        }
    }

    @Test
    fun `the two prefixes cannot collide`() {
        assertEquals("DIRECT-NS-", MediaSyncP2pProfileStore.NETWORK_NAME_PREFIX)
        assertEquals("DIRECT-RN-", MediaSyncGroupPolicy.CAMERA_NETWORK_NAME_PREFIX)
    }
}
