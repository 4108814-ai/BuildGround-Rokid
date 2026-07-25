package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncTriggerPolicyTest {
    private fun conditions(
        linkUp: Boolean = true,
        charging: Boolean = true,
        hasEligibleFiles: Boolean = true,
        cameraSessionActive: Boolean = false,
        autoSyncOnCharge: Boolean = true,
        syncInProgress: Boolean = false,
        storageReadable: Boolean = true,
    ) = MediaSyncConditions(
        linkUp = linkUp,
        charging = charging,
        hasEligibleFiles = hasEligibleFiles,
        cameraSessionActive = cameraSessionActive,
        autoSyncOnCharge = autoSyncOnCharge,
        syncInProgress = syncInProgress,
        storageReadable = storageReadable,
    )

    private fun decide(trigger: MediaSyncTrigger, conditions: MediaSyncConditions) =
        MediaSyncTriggerPolicy.decide(trigger, conditions)

    private fun skipReason(trigger: MediaSyncTrigger, conditions: MediaSyncConditions) =
        (decide(trigger, conditions) as MediaSyncTriggerDecision.Skip).reason

    @Test
    fun `charging edge with pending files starts a sync`() {
        assertEquals(
            MediaSyncTriggerDecision.Start(MediaSyncTrigger.CHARGING_EDGE),
            decide(MediaSyncTrigger.CHARGING_EDGE, conditions()),
        )
    }

    @Test
    fun `bus connect only syncs while the glasses are charging`() {
        assertEquals(
            MediaSyncTriggerDecision.Start(MediaSyncTrigger.BUS_CONNECT),
            decide(MediaSyncTrigger.BUS_CONNECT, conditions()),
        )
        assertEquals(
            MediaSyncSkipReason.NOT_CHARGING,
            skipReason(MediaSyncTrigger.BUS_CONNECT, conditions(charging = false)),
        )
    }

    @Test
    fun `manual sync ignores charging and the auto-sync switch`() {
        assertEquals(
            MediaSyncTriggerDecision.Start(MediaSyncTrigger.MANUAL),
            decide(
                MediaSyncTrigger.MANUAL,
                conditions(charging = false, autoSyncOnCharge = false),
            ),
        )
    }

    @Test
    fun `automatic triggers respect the auto-sync switch`() {
        assertEquals(
            MediaSyncSkipReason.AUTO_SYNC_OFF,
            skipReason(MediaSyncTrigger.CHARGING_EDGE, conditions(autoSyncOnCharge = false)),
        )
        assertEquals(
            MediaSyncSkipReason.AUTO_SYNC_OFF,
            skipReason(MediaSyncTrigger.BUS_CONNECT, conditions(autoSyncOnCharge = false)),
        )
    }

    @Test
    fun `a live camera session blocks every trigger including manual`() {
        MediaSyncTrigger.entries.forEach { trigger ->
            assertEquals(
                MediaSyncSkipReason.CAMERA_ACTIVE,
                skipReason(trigger, conditions(cameraSessionActive = true)),
            )
        }
    }

    @Test
    fun `an in-flight sync is never restarted, even by the camera check`() {
        assertEquals(
            MediaSyncSkipReason.ALREADY_RUNNING,
            skipReason(
                MediaSyncTrigger.MANUAL,
                conditions(syncInProgress = true, cameraSessionActive = true),
            ),
        )
    }

    @Test
    fun `missing storage access, a dead link and an empty catalog each stop the sync`() {
        assertEquals(
            MediaSyncSkipReason.STORAGE_PERMISSION,
            skipReason(MediaSyncTrigger.MANUAL, conditions(storageReadable = false)),
        )
        assertEquals(
            MediaSyncSkipReason.LINK_DOWN,
            skipReason(MediaSyncTrigger.MANUAL, conditions(linkUp = false)),
        )
        assertEquals(
            MediaSyncSkipReason.NOTHING_PENDING,
            skipReason(MediaSyncTrigger.MANUAL, conditions(hasEligibleFiles = false)),
        )
    }

    @Test
    fun `storage access is reported before the link so the wearer sees the real problem`() {
        assertEquals(
            MediaSyncSkipReason.STORAGE_PERMISSION,
            skipReason(
                MediaSyncTrigger.CHARGING_EDGE,
                conditions(storageReadable = false, linkUp = false, hasEligibleFiles = false),
            ),
        )
    }
}
