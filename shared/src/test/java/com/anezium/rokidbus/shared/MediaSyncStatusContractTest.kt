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
            settings = MediaSyncSettings(
                mode = MediaSyncMode.ALWAYS,
                deleteAfterSync = true,
                syncNormalPhotos = false,
                syncArPhotos = true,
                syncNormalVideos = true,
                syncArVideos = true,
            ),
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
        assertNull(MediaSyncStatusContract.decode(JSONObject(encoded.toString()).put("version", 99)))
    }

    @Test
    fun `a partial settings request only moves the field it carries`() {
        val current = MediaSyncSettings(mode = MediaSyncMode.ALWAYS, deleteAfterSync = false)

        val onlyDelete = MediaSyncStatusContract.applySettingsRequest(
            current,
            MediaSyncStatusContract.encodeSettingsRequest(deleteAfterSync = true),
        )

        assertEquals(MediaSyncMode.ALWAYS, onlyDelete!!.mode)
        assertTrue(onlyDelete.deleteAfterSync)
    }

    @Test
    fun `each capture filter request only moves its own field`() {
        val current = MediaSyncSettings(
            mode = MediaSyncMode.ALWAYS,
            deleteAfterSync = true,
            syncNormalPhotos = false,
            syncArPhotos = false,
            syncNormalVideos = false,
            syncArVideos = false,
        )
        val cases = listOf(
            MediaSyncStatusContract.encodeSettingsRequest(syncNormalPhotos = true) to
                current.copy(syncNormalPhotos = true),
            MediaSyncStatusContract.encodeSettingsRequest(syncArPhotos = true) to
                current.copy(syncArPhotos = true),
            MediaSyncStatusContract.encodeSettingsRequest(syncNormalVideos = true) to
                current.copy(syncNormalVideos = true),
            MediaSyncStatusContract.encodeSettingsRequest(syncArVideos = true) to
                current.copy(syncArVideos = true),
        )

        cases.forEach { (request, expected) ->
            assertEquals(expected, MediaSyncStatusContract.applySettingsRequest(current, request))
        }
    }

    @Test
    fun `an empty settings request is a refresh, not a reset`() {
        val current = MediaSyncSettings(mode = MediaSyncMode.MANUAL, deleteAfterSync = true)

        val unchanged = MediaSyncStatusContract.applySettingsRequest(
            current,
            MediaSyncStatusContract.encodeSettingsRequest(),
        )

        assertEquals(current, unchanged)
    }

    @Test
    fun `non boolean settings values are refused`() {
        listOf(
            "deleteAfterSync",
            "syncNormalPhotos",
            "syncArPhotos",
            "syncNormalVideos",
            "syncArVideos",
        ).forEach { key ->
            val bogus = JSONObject()
                .put("version", MediaSyncStatusContract.VERSION)
                .put(key, "yes")
            assertNull(MediaSyncStatusContract.applySettingsRequest(MediaSyncSettings(), bogus))
        }
        assertNull(
            MediaSyncStatusContract.applySettingsRequest(
                MediaSyncSettings(),
                JSONObject().put("version", 7),
            ),
        )
    }

    @Test
    fun `settings defaults only enable normal photos`() {
        val defaults = MediaSyncSettings()

        assertEquals(MediaSyncMode.CHARGING, defaults.mode)
        assertFalse(defaults.deleteAfterSync)
        assertTrue(defaults.syncNormalPhotos)
        assertFalse(defaults.syncArPhotos)
        assertFalse(defaults.syncNormalVideos)
        assertFalse(defaults.syncArVideos)
    }

    @Test
    fun `an old status without capture filters uses the new defaults`() {
        val encoded = MediaSyncStatusContract.encode(
            MediaSyncStatus(
                settings = MediaSyncSettings(
                    syncNormalPhotos = false,
                    syncArPhotos = true,
                    syncNormalVideos = true,
                    syncArVideos = true,
                ),
            ),
        )
        listOf(
            "syncNormalPhotos",
            "syncArPhotos",
            "syncNormalVideos",
            "syncArVideos",
        ).forEach(encoded::remove)

        val decoded = MediaSyncStatusContract.decode(encoded)

        assertEquals(MediaSyncSettings(), decoded?.settings)
    }

    @Test
    fun `allows maps every capture type to its setting`() {
        MediaSyncCaptureType.entries.forEach { enabledType ->
            val settings = MediaSyncSettings(
                syncNormalPhotos = enabledType == MediaSyncCaptureType.PHOTO,
                syncArPhotos = enabledType == MediaSyncCaptureType.PHOTO_AR,
                syncNormalVideos = enabledType == MediaSyncCaptureType.VIDEO,
                syncArVideos = enabledType == MediaSyncCaptureType.VIDEO_AR,
            )

            MediaSyncCaptureType.entries.forEach { type ->
                assertEquals(type == enabledType, settings.allows(type))
            }
        }
    }

    @Test
    fun `every sync mode survives the wire`() {
        MediaSyncMode.entries.forEach { mode ->
            val decoded = MediaSyncStatusContract.decode(
                MediaSyncStatusContract.encode(MediaSyncStatus(settings = MediaSyncSettings(mode = mode))),
            )
            assertEquals(mode, decoded?.settings?.mode)
        }
    }

    @Test
    fun `an unknown sync mode is refused rather than guessed`() {
        assertNull(
            MediaSyncStatusContract.applySettingsRequest(
                MediaSyncSettings(),
                JSONObject()
                    .put("version", MediaSyncStatusContract.VERSION)
                    .put("syncMode", "whenever"),
            ),
        )
    }
}
