package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeEnvelopePolicyTest {
    @Test
    fun `show accepts an image frame while update still rejects it`() {
        val bytes = jpeg(width = 480, height = 160)
        val payload = JSONObject()
            .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("kind", NoticeSurfaceContract.KIND)
            .put("title", "Marie")
            .put("imageVersion", ImageSurfaceContract.VERSION)
            .put("contentKey", "message-photo")
            .put("mimeType", ImageSurfaceContract.MIME_JPEG)
            .put("pixelWidth", 480)
            .put("pixelHeight", 160)
            .put("sha256", ImageSurfaceContract.sha256(bytes))

        assertTrue(
            isValidLocalNoticeEnvelope(
                BusEnvelope(BusPaths.NOTICE_SHOW, payload = payload, binary = bytes),
            ),
        )
        assertFalse(
            isValidLocalNoticeEnvelope(
                BusEnvelope(BusPaths.NOTICE_UPDATE, payload = payload, binary = bytes),
            ),
        )
        assertTrue(
            isValidLocalNoticeEnvelope(
                BusEnvelope(
                    BusPaths.NOTICE_UPDATE,
                    payload = JSONObject()
                        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
                        .put("body", "Five minutes out"),
                ),
            ),
        )
        assertFalse(
            isValidLocalNoticeEnvelope(
                BusEnvelope(
                    BusPaths.NOTICE_UPDATE,
                    payload = JSONObject()
                        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
                        .put("wakeDisplay", true),
                ),
            ),
        )
        assertFalse(
            isValidLocalNoticeEnvelope(
                BusEnvelope(
                    BusPaths.NOTICE_UPDATE,
                    payload = JSONObject()
                        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
                        .put("backdrop", true),
                ),
            ),
        )
    }

    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteArray(128).also { bytes ->
            byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
                0x00, 0x11, 0x08,
                (height ushr 8).toByte(), height.toByte(),
                (width ushr 8).toByte(), width.toByte(),
                0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                0xff.toByte(), 0xd9.toByte(),
            ).copyInto(bytes)
        }
}
