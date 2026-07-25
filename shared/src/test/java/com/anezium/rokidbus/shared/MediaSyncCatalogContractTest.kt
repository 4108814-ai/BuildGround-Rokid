package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MediaSyncCatalogContractTest {
    @Test
    fun `catalog round trip preserves order size and mtime`() {
        val items = listOf(
            MediaSyncItem("img-20260710-175956-a0-N1-2.jpg", 3_145_728L, 1_752_170_396_000L),
            MediaSyncItem("vid-20260710-180402-a0-N1-2.mp4", 41_000_000L, 1_752_170_642_000L),
        )

        val decoded = MediaSyncCatalogContract.decode(MediaSyncCatalogContract.encode(items, true))

        assertEquals(items, decoded?.items)
        assertTrue(decoded!!.truncated)
    }

    @Test
    fun `empty catalog decodes as empty rather than null`() {
        val decoded = MediaSyncCatalogContract.decode(
            MediaSyncCatalogContract.encode(emptyList(), false),
        )

        assertEquals(emptyList<MediaSyncItem>(), decoded?.items)
        assertFalse(decoded!!.truncated)
    }

    @Test
    fun `malformed catalogs are rejected`() {
        assertNull(MediaSyncCatalogContract.decode(JSONObject().put("version", 2)))
        assertNull(MediaSyncCatalogContract.decode(JSONObject().put("version", 1)))
        val duplicated = MediaSyncCatalogContract.encode(
            listOf(MediaSyncItem("a.jpg", 1L, 1L)),
            false,
        )
        duplicated.getJSONArray("items").put(JSONObject().put("name", "a.jpg").put("size", 1).put("mtime", 1))
        assertNull(MediaSyncCatalogContract.decode(duplicated))
        val negative = MediaSyncCatalogContract.encode(listOf(MediaSyncItem("a.jpg", 1L, 1L)), false)
        negative.getJSONArray("items").getJSONObject(0).put("size", -1)
        assertNull(MediaSyncCatalogContract.decode(negative))
    }

    @Test
    fun `names that could escape the capture directory are refused`() {
        assertTrue(MediaSyncCatalogContract.isSafeName("img-20260710-175956-a0-N1-2.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName(".."))
        assertFalse(MediaSyncCatalogContract.isSafeName("../secrets.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName("sub/dir.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName("back\\slash.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName(".hidden.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName("with space.jpg"))
        assertFalse(MediaSyncCatalogContract.isSafeName(""))
    }

    @Test
    fun `supported extensions cover photos and videos regardless of prefix`() {
        assertTrue(MediaSyncMediaFile.isSupported("img-20260710-175956-a0-N1-2.jpg"))
        assertTrue(MediaSyncMediaFile.isSupported("vid-20260710-175956.MP4"))
        assertTrue(MediaSyncMediaFile.isSupported("anything.heic"))
        assertFalse(MediaSyncMediaFile.isSupported("notes.txt"))
        assertFalse(MediaSyncMediaFile.isSupported("noextension"))
        assertTrue(MediaSyncMediaFile.isVideo("clip.mov"))
        assertFalse(MediaSyncMediaFile.isVideo("photo.png"))
        assertEquals("image/jpeg", MediaSyncMediaFile.mimeType("a.JPEG"))
        assertEquals("video/mp4", MediaSyncMediaFile.mimeType("a.mp4"))
        assertEquals("application/octet-stream", MediaSyncMediaFile.mimeType("a.bin"))
    }

    @Test
    fun `capture filename yields the capture instant`() {
        val utc = TimeZone.getTimeZone("UTC")

        val millis = MediaSyncMediaFile.capturedAtMillis("img-20260710-175956-a0-N1-2.jpg", utc)

        // 2026-07-10T17:59:56Z
        assertEquals(1_783_706_396_000L, millis)
        assertEquals(
            millis,
            MediaSyncMediaFile.capturedAtMillis("vid-20260710-175956.mp4", utc),
        )
    }

    @Test
    fun `filenames without a usable timestamp fall back to null`() {
        val utc = TimeZone.getTimeZone("UTC")

        assertNull(MediaSyncMediaFile.capturedAtMillis("IMG_0042.jpg", utc))
        assertNull(MediaSyncMediaFile.capturedAtMillis("img-20261310-175956-a0.jpg", utc))
        assertNull(MediaSyncMediaFile.capturedAtMillis("img-20260710-995956-a0.jpg", utc))
        assertNull(MediaSyncMediaFile.capturedAtMillis("img-19990101-000000.jpg", utc))
    }
}
