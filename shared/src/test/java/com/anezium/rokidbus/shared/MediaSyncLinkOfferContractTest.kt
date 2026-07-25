package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncLinkOfferContractTest {
    private val offer = MediaSyncLinkOffer(
        sessionId = "media-sync-session",
        ssid = "DIRECT-NS-ab3d9k",
        passphrase = "abcdefgh12345678abcdefgh",
        goIp = "192.168.49.1",
        port = 38_403,
        token = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `offer round trip preserves every credential`() {
        assertEquals(offer, MediaSyncLinkOfferContract.decode(MediaSyncLinkOfferContract.encode(offer)))
    }

    @Test
    fun `incomplete or out of range offers are rejected`() {
        fun mutated(block: JSONObject.() -> Unit) =
            MediaSyncLinkOfferContract.decode(
                JSONObject(MediaSyncLinkOfferContract.encode(offer).toString()).apply(block),
            )

        assertNull(mutated { put("version", 2) })
        assertNull(mutated { put("ssid", "") })
        assertNull(mutated { put("passphrase", "short") })
        assertNull(mutated { put("goIp", "") })
        assertNull(mutated { put("port", 0) })
        assertNull(mutated { put("port", 70_000) })
        assertNull(mutated { put("token", "tooshort") })
        assertNull(mutated { put("sessionId", "") })
    }

    @Test
    fun `the media sync SSID prefix stays clear of the camera link`() {
        assertTrue(offer.ssid.startsWith("DIRECT-NS-"))
        assertFalse(offer.ssid.startsWith("DIRECT-RN-"))
    }

    @Test
    fun `media sync paths are protected and mostly hub only`() {
        assertTrue(BusPaths.isProtectedMediaSyncPath(BusPaths.MEDIA_SYNC_STATUS))
        assertTrue(BusPaths.isProtectedMediaSyncPath(BusPaths.MEDIA_SYNC_LINK_OFFER))
        assertFalse(BusPaths.isProtectedMediaSyncPath("/mediasyncfake/status"))
        assertFalse(BusPaths.isProtectedMediaSyncPath("/camera/overlay"))

        assertFalse(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_SETTINGS))
        assertFalse(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_NOW))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_STATUS))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_LINK_OFFER))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_CONFIG))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_TRIGGER))
        assertTrue(BusPaths.isHubOnlyMediaSyncPath(BusPaths.MEDIA_SYNC_STATE))
    }
}
