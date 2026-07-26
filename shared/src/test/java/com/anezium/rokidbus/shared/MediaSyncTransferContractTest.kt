package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncTransferContractTest {
    private val session = "session-1"

    @Test
    fun `every data-plane message carries its session`() {
        listOf(
            MediaSyncTransferContract.sessionJson(session),
            MediaSyncTransferContract.fileRequest(session, "a.jpg", 10L),
            MediaSyncTransferContract.fileBegin(session, "a.jpg", 100L, 1L, 10L),
            MediaSyncTransferContract.chunkMeta(session, "a.jpg", 3, 96L),
            MediaSyncTransferContract.fileEnd(session, "a.jpg", "abc"),
            MediaSyncTransferContract.fileAck(session, "a.jpg", ok = true, delete = false),
            MediaSyncTransferContract.fileError(session, "a.jpg", "not_found"),
            MediaSyncTransferContract.deleteResult(session, "a.jpg", "deleted"),
            MediaSyncTransferContract.abort(session, "camera_active"),
        ).forEach { payload ->
            assertTrue(payload.toString(), MediaSyncTransferContract.isForSession(payload, session))
        }
    }

    @Test
    fun `a stale session is never acted on`() {
        val payload = MediaSyncTransferContract.fileRequest(session, "a.jpg", 0L)

        assertFalse(MediaSyncTransferContract.isForSession(payload, "session-2"))
        assertFalse(MediaSyncTransferContract.isForSession(payload, ""))
        assertFalse(
            MediaSyncTransferContract.isForSession(
                JSONObject(payload.toString()).put("version", 99),
                session,
            ),
        )
    }

    @Test
    fun `offsets and names round trip`() {
        val payload = MediaSyncTransferContract.fileRequest(session, "img-1.jpg", 4_096L)

        assertEquals("img-1.jpg", MediaSyncTransferContract.name(payload))
        assertEquals(4_096L, MediaSyncTransferContract.offset(payload))
    }

    @Test
    fun `a missing or negative offset reads as zero`() {
        assertEquals(0L, MediaSyncTransferContract.offset(JSONObject()))
        assertEquals(0L, MediaSyncTransferContract.offset(JSONObject().put("offset", -12)))
        assertNull(MediaSyncTransferContract.name(JSONObject().put("name", "")))
    }

    @Test
    fun `chunk metadata carries the sequence and the absolute offset`() {
        val payload = MediaSyncTransferContract.chunkMeta(session, "v.mp4", 7, 229_376L)

        assertEquals(7, payload.getInt("seq"))
        assertEquals(229_376L, MediaSyncTransferContract.offset(payload))
    }

    @Test
    fun `the ack states plainly whether the glasses may delete`() {
        val keep = MediaSyncTransferContract.fileAck(session, "a.jpg", ok = true, delete = false)
        val remove = MediaSyncTransferContract.fileAck(session, "a.jpg", ok = true, delete = true)

        assertTrue(keep.getBoolean("ok"))
        assertFalse(keep.getBoolean("delete"))
        assertTrue(remove.getBoolean("delete"))
    }

    @Test
    fun `transfer paths are recognised and protected, control paths are not transfer`() {
        listOf(
            BusPaths.MEDIA_SYNC_XFER_CATALOG,
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            BusPaths.MEDIA_SYNC_XFER_BYE,
        ).forEach { path ->
            assertTrue(path, BusPaths.isMediaSyncTransferPath(path))
            assertTrue(path, BusPaths.isProtectedMediaSyncPath(path))
            assertTrue(path, BusPaths.isHubOnlyMediaSyncPath(path))
        }
        assertFalse(BusPaths.isMediaSyncTransferPath(BusPaths.MEDIA_SYNC_STATUS))
        assertFalse(BusPaths.isMediaSyncTransferPath(BusPaths.MEDIA_SYNC_SETTINGS))
        assertFalse(BusPaths.isMediaSyncTransferPath("/mediasync/xferfake/x"))
    }

    @Test
    fun `the plugin-facing paths stay plugin-facing`() {
        assertFalse(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_SETTINGS))
        assertFalse(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_NOW))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_CONFIG_REQUEST))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_STATE))
    }

    @Test
    fun `a chunk fits the SPP frame with room for the envelope header`() {
        assertTrue(MediaSyncTransferContract.CHUNK_BYTES < 2 * 1024 * 1024)
        assertTrue(MediaSyncTransferContract.MAX_CHUNK_BYTES < 2 * 1024 * 1024)
    }
}
