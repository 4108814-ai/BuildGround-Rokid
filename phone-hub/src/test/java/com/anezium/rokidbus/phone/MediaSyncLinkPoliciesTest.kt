package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.mediasync.MediaSyncBlockerMapping
import com.anezium.rokidbus.phone.mediasync.MediaSyncDiscoveryPrimingPolicy
import com.anezium.rokidbus.phone.mediasync.MediaSyncJoinRecoveryAction
import com.anezium.rokidbus.phone.mediasync.MediaSyncJoinRecoveryPolicy
import com.anezium.rokidbus.phone.mediasync.MediaSyncJoinRetryPolicy
import com.anezium.rokidbus.phone.mediasync.MediaSyncLinkBlocker
import com.anezium.rokidbus.phone.mediasync.MediaSyncSingleDispatch
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncLinkPoliciesTest {
    @Test
    fun `join attempts are budgeted and back off linearly`() {
        val policy = MediaSyncJoinRetryPolicy(
            initialDelayMs = 300L,
            delayStepMs = 1_000L,
            maxDelayMs = 3_000L,
            maxAttempts = 6,
        )

        assertEquals(listOf(1, 2, 3, 4, 5, 6), (1..6).map { policy.startAttempt() })
        assertNull(policy.startAttempt())
        assertEquals(300L, policy.retryDelayAfter(1))
        assertEquals(1_300L, policy.retryDelayAfter(2))
        assertEquals(3_000L, policy.retryDelayAfter(5))
        assertNull(policy.retryDelayAfter(6))

        policy.reset()
        assertEquals(1, policy.startAttempt())
    }

    @Test
    fun `recovery escalates exactly at the second and fourth failure`() {
        val policy = MediaSyncJoinRecoveryPolicy()

        assertEquals(MediaSyncJoinRecoveryAction.NONE, policy.actionAfter(0))
        assertEquals(MediaSyncJoinRecoveryAction.NONE, policy.actionAfter(1))
        assertEquals(MediaSyncJoinRecoveryAction.REMOVE_GROUP, policy.actionAfter(2))
        assertEquals(MediaSyncJoinRecoveryAction.NONE, policy.actionAfter(3))
        assertEquals(MediaSyncJoinRecoveryAction.RESET_CHANNEL, policy.actionAfter(4))
        assertEquals(MediaSyncJoinRecoveryAction.NONE, policy.actionAfter(5))
    }

    @Test
    fun `discovery is primed once per join cycle`() {
        val policy = MediaSyncDiscoveryPrimingPolicy()

        assertTrue(policy.decision(alreadyPrimedForJoinCycle = false).shouldPrime)
        assertFalse(policy.decision(alreadyPrimedForJoinCycle = true).shouldPrime)
        assertEquals(2_000L, policy.decision(false).discoveryWaitMs)
        assertEquals(400L, policy.decision(false).stopCallbackFallbackMs)
    }

    @Test
    fun `only the first caller in a priming cycle gets to join`() {
        val dispatch = MediaSyncSingleDispatch()

        assertFalse(dispatch.isClaimed())
        assertTrue(dispatch.claim())
        assertTrue(dispatch.isClaimed())
        // The stop-discovery callback and the fallback timer both reach the join; a second
        // connect() inside a healthy join is what inflates failures and triggers removeGroup.
        assertFalse(dispatch.claim())
        assertFalse(dispatch.claim())
    }

    @Test
    fun `each priming cycle gets its own claim`() {
        assertTrue(MediaSyncSingleDispatch().claim())
        assertTrue(MediaSyncSingleDispatch().claim())
    }

    @Test
    fun `glasses skip reasons map to the line the wearer reads`() {
        assertEquals(
            MediaSyncBlocker.GLASSES_STORAGE_PERMISSION,
            MediaSyncBlockerMapping.fromGlassesReason("storage_permission"),
        )
        assertEquals(
            MediaSyncBlocker.CAMERA_ACTIVE,
            MediaSyncBlockerMapping.fromGlassesReason("camera_active"),
        )
        assertEquals(
            MediaSyncBlocker.NOTHING_PENDING,
            MediaSyncBlockerMapping.fromGlassesReason("nothing_pending"),
        )
        assertNull(MediaSyncBlockerMapping.fromGlassesReason("already_running"))
        assertNull(MediaSyncBlockerMapping.fromGlassesReason(null))
    }

    @Test
    fun `a parked camera group reads as the camera holding the radio`() {
        assertEquals(
            MediaSyncBlocker.CAMERA_ACTIVE,
            MediaSyncBlockerMapping.fromGlassesReason(
                MediaSyncStatusContract.REASON_CAMERA_GROUP_PARKED,
            ),
        )
    }

    @Test
    fun `a denied nearby-devices grant never masquerades as Wi-Fi being off`() {
        assertEquals(
            MediaSyncBlocker.PHONE_WIFI_OFF,
            MediaSyncBlockerMapping.fromLinkBlocker(MediaSyncLinkBlocker.PHONE_WIFI_OFF),
        )
        assertEquals(
            MediaSyncBlocker.PHONE_PERMISSION,
            MediaSyncBlockerMapping.fromLinkBlocker(MediaSyncLinkBlocker.MISSING_PERMISSION),
        )
        assertEquals(
            MediaSyncBlocker.PHONE_WIFI_OFF,
            MediaSyncBlockerMapping.fromLinkBlocker(MediaSyncLinkBlocker.NO_P2P_SERVICE),
        )
    }
}
