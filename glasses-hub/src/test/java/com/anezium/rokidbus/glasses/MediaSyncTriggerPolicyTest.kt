package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.MediaSyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncTriggerPolicyTest {
    private fun conditions(
        linkUp: Boolean = true,
        charging: Boolean = true,
        hasEligibleFiles: Boolean = true,
        cameraSessionActive: Boolean = false,
        mode: MediaSyncMode = MediaSyncMode.CHARGING,
        syncInProgress: Boolean = false,
        storageReadable: Boolean = true,
    ) = MediaSyncConditions(
        linkUp = linkUp,
        charging = charging,
        hasEligibleFiles = hasEligibleFiles,
        cameraSessionActive = cameraSessionActive,
        mode = mode,
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
    fun `the button works in every mode, charging or not`() {
        assertEquals(
            MediaSyncTriggerDecision.Start(MediaSyncTrigger.MANUAL),
            decide(
                MediaSyncTrigger.MANUAL,
                conditions(charging = false, mode = MediaSyncMode.MANUAL),
            ),
        )
    }

    @Test
    fun `manual-only mode blocks every automatic trigger`() {
        listOf(
            MediaSyncTrigger.CHARGING_EDGE,
            MediaSyncTrigger.BUS_CONNECT,
            MediaSyncTrigger.NEW_CAPTURE,
        ).forEach { trigger ->
            assertEquals(
                MediaSyncSkipReason.AUTO_SYNC_OFF,
                skipReason(trigger, conditions(mode = MediaSyncMode.MANUAL)),
            )
        }
    }

    @Test
    fun `always mode syncs off the charger, including straight after a capture`() {
        listOf(
            MediaSyncTrigger.NEW_CAPTURE,
            MediaSyncTrigger.BUS_CONNECT,
            MediaSyncTrigger.CHARGING_EDGE,
        ).forEach { trigger ->
            assertEquals(
                MediaSyncTriggerDecision.Start(trigger),
                decide(trigger, conditions(mode = MediaSyncMode.ALWAYS, charging = false)),
            )
        }
    }

    @Test
    fun `charging mode holds a new capture until the glasses are on the charger`() {
        assertEquals(
            MediaSyncSkipReason.NOT_CHARGING,
            skipReason(
                MediaSyncTrigger.NEW_CAPTURE,
                conditions(mode = MediaSyncMode.CHARGING, charging = false),
            ),
        )
        assertEquals(
            MediaSyncTriggerDecision.Start(MediaSyncTrigger.NEW_CAPTURE),
            decide(
                MediaSyncTrigger.NEW_CAPTURE,
                conditions(mode = MediaSyncMode.CHARGING, charging = true),
            ),
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
