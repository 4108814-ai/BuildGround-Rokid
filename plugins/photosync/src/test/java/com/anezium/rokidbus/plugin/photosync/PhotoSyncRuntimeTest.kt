package com.anezium.rokidbus.plugin.photosync

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncSettings
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhotoSyncRuntimeTest {
    private class FakeHost : PhotoSyncHost {
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val logs = mutableListOf<String>()
        override fun send(path: String, payload: JSONObject): Boolean {
            sends += path to payload
            return true
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private val host = FakeHost()
    private val runtime = PhotoSyncRuntime(host)

    @Test
    fun `a status push updates the runtime and every observer`() {
        val seen = mutableListOf<MediaSyncStatus?>()
        runtime.observe { seen += it }
        val status = MediaSyncStatus(state = MediaSyncState.TRANSFERRING, syncedTotal = 12)

        runtime.onMessage(BusPaths.MEDIA_SYNC_STATUS, MediaSyncStatusContract.encode(status))

        assertEquals(listOf(null, status), seen)
        assertEquals(status, runtime.status)
    }

    @Test
    fun `unobserving stops the callbacks`() {
        val seen = mutableListOf<MediaSyncStatus?>()
        val unobserve = runtime.observe { seen += it }
        unobserve()

        runtime.onMessage(
            BusPaths.MEDIA_SYNC_STATUS,
            MediaSyncStatusContract.encode(MediaSyncStatus()),
        )

        assertEquals(listOf<MediaSyncStatus?>(null), seen)
    }

    @Test
    fun `foreign paths and malformed payloads never become status`() {
        runtime.onMessage("/plugin/photosync/ping", JSONObject().put("hello", true))
        runtime.onMessage(BusPaths.MEDIA_SYNC_STATUS, JSONObject().put("version", 99))

        assertNull(runtime.status)
        assertEquals(1, host.logs.size)
    }

    @Test
    fun `each control sends exactly one bus message on the right path`() {
        runtime.setSyncMode(MediaSyncMode.ALWAYS)
        runtime.setDeleteAfterSync(true)
        runtime.syncNow()
        runtime.refresh()

        assertEquals(
            listOf(
                BusPaths.MEDIA_SYNC_SETTINGS,
                BusPaths.MEDIA_SYNC_SETTINGS,
                BusPaths.MEDIA_SYNC_NOW,
                BusPaths.MEDIA_SYNC_SETTINGS,
            ),
            host.sends.map { it.first },
        )
        assertEquals("always", host.sends[0].second.getString("syncMode"))
        assertEquals(false, host.sends[0].second.has("deleteAfterSync"))
        assertEquals(true, host.sends[1].second.getBoolean("deleteAfterSync"))
        assertEquals(false, host.sends[3].second.has("syncMode"))
    }

    @Test
    fun `settings requests only carry the field the wearer touched`() {
        val current = MediaSyncSettings(mode = MediaSyncMode.CHARGING, deleteAfterSync = false)
        runtime.setDeleteAfterSync(true)

        val applied = MediaSyncStatusContract.applySettingsRequest(current, host.sends.single().second)

        assertEquals(MediaSyncSettings(mode = MediaSyncMode.CHARGING, deleteAfterSync = true), applied)
    }

    @Test
    fun `each capture filter setter sends its own partial settings request`() {
        runtime.setSyncNormalPhotos(false)
        runtime.setSyncArPhotos(true)
        runtime.setSyncNormalVideos(true)
        runtime.setSyncArVideos(true)

        val expected = listOf(
            "syncNormalPhotos" to false,
            "syncArPhotos" to true,
            "syncNormalVideos" to true,
            "syncArVideos" to true,
        )
        assertEquals(List(4) { BusPaths.MEDIA_SYNC_SETTINGS }, host.sends.map { it.first })
        expected.zip(host.sends.map { it.second }).forEach { (entry, payload) ->
            val (key, value) = entry
            assertEquals(2, payload.length())
            assertEquals(value, payload.getBoolean(key))
        }
    }

    @Test
    fun `losing the hub clears the mirrored status`() {
        runtime.onMessage(
            BusPaths.MEDIA_SYNC_STATUS,
            MediaSyncStatusContract.encode(MediaSyncStatus(syncedTotal = 4)),
        )
        val seen = mutableListOf<MediaSyncStatus?>()
        runtime.observe { seen += it }

        runtime.onDisconnected()

        assertNull(runtime.status)
        assertNull(seen.last())
    }

    @Test
    fun `the headline names the real reason nothing is happening`() {
        assertEquals("Connecting to Rokid Nexus", PhotoSyncCopy.headline(null))
        assertEquals("Ready", PhotoSyncCopy.headline(MediaSyncStatus()))
        assertEquals(
            "Up to date",
            PhotoSyncCopy.headline(MediaSyncStatus(blocker = MediaSyncBlocker.NOTHING_PENDING)),
        )
        assertEquals(
            "Waiting for the glasses to charge",
            PhotoSyncCopy.headline(MediaSyncStatus(blocker = MediaSyncBlocker.NOT_CHARGING)),
        )
        assertEquals(
            "Paused while the camera is open",
            PhotoSyncCopy.headline(MediaSyncStatus(blocker = MediaSyncBlocker.CAMERA_ACTIVE)),
        )
        assertEquals(
            "Allow storage access on the glasses",
            PhotoSyncCopy.headline(
                MediaSyncStatus(blocker = MediaSyncBlocker.GLASSES_STORAGE_PERMISSION),
            ),
        )
        assertEquals("Connecting to the glasses", PhotoSyncCopy.headline(MediaSyncStatus(state = MediaSyncState.PREPARING)))
    }

    @Test
    fun `every blocker has copy`() {
        MediaSyncBlocker.entries.forEach { blocker ->
            val headline = PhotoSyncCopy.headline(MediaSyncStatus(blocker = blocker))
            assertTrue("$blocker has no copy", headline.isNotBlank() && headline != "Ready")
        }
    }

    @Test
    fun `transfer progress counts the file being worked on, never past the total`() {
        fun headline(done: Int, total: Int) = PhotoSyncCopy.headline(
            MediaSyncStatus(
                state = MediaSyncState.TRANSFERRING,
                progress = MediaSyncProgress(filesDone = done, filesTotal = total),
            ),
        )

        assertEquals("Syncing 1 of 12", headline(0, 12))
        assertEquals("Syncing 12 of 12", headline(11, 12))
        assertEquals("Syncing 12 of 12", headline(12, 12))
        assertEquals("Syncing", headline(0, 0))
    }

    @Test
    fun `byte sizes read like a gallery, not like a debugger`() {
        assertEquals("512 B", PhotoSyncCopy.formatBytes(512, Locale.US))
        assertEquals("2 KB", PhotoSyncCopy.formatBytes(2_048, Locale.US))
        assertEquals("3.1 MB", PhotoSyncCopy.formatBytes(3_145_728, Locale.US))
        assertEquals("1.2 GB", PhotoSyncCopy.formatBytes(1_200_000_000, Locale.US))
    }

    @Test
    fun `sizes follow the reader's locale, not the developer's`() {
        assertEquals("3,1 MB", PhotoSyncCopy.formatBytes(3_145_728, Locale.FRANCE))
    }

    @Test
    fun `run summaries stay short and singularise correctly`() {
        assertEquals(
            "1 file · 3.1 MB",
            PhotoSyncCopy.describe(
                MediaSyncRun(0L, MediaSyncResult.COMPLETED, 1, 3_145_728L, 0, 0),
                Locale.US,
            ),
        )
        assertEquals(
            "9 files · 41.0 MB",
            PhotoSyncCopy.describe(
                MediaSyncRun(0L, MediaSyncResult.COMPLETED, 9, 41_000_000L, 0, 0),
                Locale.US,
            ),
        )
        assertEquals(
            "Nothing new",
            PhotoSyncCopy.describe(MediaSyncRun(0L, MediaSyncResult.UP_TO_DATE, 0, 0L, 0, 0)),
        )
        assertEquals(
            "Could not join the glasses",
            PhotoSyncCopy.describe(
                MediaSyncRun(0L, MediaSyncResult.FAILED, 0, 0L, 0, 0, "Could not join the glasses"),
            ),
        )
    }
}
