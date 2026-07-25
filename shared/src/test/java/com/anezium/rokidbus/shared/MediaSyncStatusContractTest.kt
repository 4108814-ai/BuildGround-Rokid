package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncStatusContractTest {
    @Test
    fun `status round trip preserves state progress settings and history`() {
        val status = MediaSyncStatus(
            state = MediaSyncState.TRANSFERRING,
            blocker = null,
            settings = MediaSyncSettings(autoSyncOnCharge = false, deleteAfterSync = true),
            progress = MediaSyncProgress(3, 12, 4_200_000L, 18_600_000L, "img-1.jpg"),
            history = listOf(
                MediaSyncRun(1_752_170_396_000L, MediaSyncResult.COMPLETED, 12, 18_600_000L, 0, 12),
                MediaSyncRun(1_752_070_396_000L, MediaSyncResult.PARTIAL, 3, 900L, 2, 0, "Interrupted"),
            ),
            syncedTotal = 142,
            deletionSupported = false,
        )

        assertEquals(status, MediaSyncStatusContract.decode(MediaSyncStatusContract.encode(status)))
    }

    @Test
    fun `an idle blocked status keeps its reason`() {
        val status = MediaSyncStatus(
            state = MediaSyncState.IDLE,
            blocker = MediaSyncBlocker.GLASSES_STORAGE_PERMISSION,
        )

        val decoded = MediaSyncStatusContract.decode(MediaSyncStatusContract.encode(status))

        assertEquals(MediaSyncBlocker.GLASSES_STORAGE_PERMISSION, decoded?.blocker)
        assertNull(decoded?.deletionSupported)
    }

    @Test
    fun `history is capped so a long-running hub cannot grow the payload`() {
        val runs = (1..20).map {
            MediaSyncRun(it.toLong(), MediaSyncResult.COMPLETED, it, it.toLong(), 0, 0)
        }

        val decoded = MediaSyncStatusContract.decode(
            MediaSyncStatusContract.encode(MediaSyncStatus(history = runs)),
        )

        assertEquals(MediaSyncStatusContract.MAX_HISTORY, decoded?.history?.size)
        assertEquals(1L, decoded?.history?.first()?.finishedAtMillis)
    }

    @Test
    fun `unknown state and result values are rejected instead of guessed`() {
        val encoded = MediaSyncStatusContract.encode(MediaSyncStatus())
        assertNull(MediaSyncStatusContract.decode(JSONObject(encoded.toString()).put("state", "warping")))
        assertNull(MediaSyncStatusContract.decode(JSONObject(encoded.toString()).put("blocker", "gremlins")))
        assertNull(MediaSyncStatusContract.decode(JSONObject(encoded.toString()).put("version", 2)))
    }

    @Test
    fun `a partial settings request only moves the field it carries`() {
        val current = MediaSyncSettings(autoSyncOnCharge = true, deleteAfterSync = false)

        val onlyDelete = MediaSyncStatusContract.applySettingsRequest(
            current,
            MediaSyncStatusContract.encodeSettingsRequest(deleteAfterSync = true),
        )

        assertTrue(onlyDelete!!.autoSyncOnCharge)
        assertTrue(onlyDelete.deleteAfterSync)
    }

    @Test
    fun `an empty settings request is a refresh, not a reset`() {
        val current = MediaSyncSettings(autoSyncOnCharge = false, deleteAfterSync = true)

        val unchanged = MediaSyncStatusContract.applySettingsRequest(
            current,
            MediaSyncStatusContract.encodeSettingsRequest(),
        )

        assertEquals(current, unchanged)
    }

    @Test
    fun `non boolean settings values are refused`() {
        val bogus = JSONObject()
            .put("version", MediaSyncStatusContract.VERSION)
            .put("deleteAfterSync", "yes")

        assertNull(MediaSyncStatusContract.applySettingsRequest(MediaSyncSettings(), bogus))
        assertNull(
            MediaSyncStatusContract.applySettingsRequest(
                MediaSyncSettings(),
                JSONObject().put("version", 7),
            ),
        )
    }

    @Test
    fun `defaults are auto sync on and delete off`() {
        val defaults = MediaSyncSettings()

        assertTrue(defaults.autoSyncOnCharge)
        assertFalse(defaults.deleteAfterSync)
    }
}
