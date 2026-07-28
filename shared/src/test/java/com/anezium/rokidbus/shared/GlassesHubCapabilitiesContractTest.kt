package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesHubCapabilitiesContractTest {
    @Test
    fun `capabilities carry the optional glasses version name`() {
        val capabilities = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.IMAGE_SURFACE or
                BusCapabilityBits.PIN_SURFACE or
                BusCapabilityBits.ACTIVITY_SURFACE,
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            pinSurfaceVersion = PinSurfaceContract.VERSION,
            activitySurfaceVersion = ActivitySurfaceContract.VERSION,
            maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
            versionName = " 1.0.1 ",
            setupComplete = true,
        )
        val payload = GlassesHubCapabilitiesContract.toJson(capabilities)
            .put("futureField", true)
        val parsed = GlassesHubCapabilitiesContract.parse(payload)

        assertEquals("1.0.1", payload.getString("versionName"))
        assertTrue(payload.getBoolean("setupComplete"))
        assertEquals("1.0.1", parsed.versionName)
        assertTrue(parsed.setupComplete)
        assertEquals(
            BusCapabilityBits.IMAGE_SURFACE or
                BusCapabilityBits.PIN_SURFACE or
                BusCapabilityBits.ACTIVITY_SURFACE,
            parsed.features,
        )
        assertEquals(PinSurfaceContract.VERSION, parsed.pinSurfaceVersion)
        assertEquals(ActivitySurfaceContract.VERSION, parsed.activitySurfaceVersion)
        assertEquals(128, BusCapabilityBits.ACTIVITY_SURFACE)
    }

    @Test
    fun `legacy capabilities without a glasses version remain valid`() {
        val legacyPayload = JSONObject()
            .put("version", GlassesHubCapabilitiesContract.VERSION)
            .put("features", BusCapabilityBits.IMAGE_SURFACE)
            .put("imageSurfaceVersion", ImageSurfaceContract.VERSION)
            .put("maxImageBytes", ImageSurfaceContract.MAX_IMAGE_BYTES)
        val parsed = GlassesHubCapabilitiesContract.parse(legacyPayload)
        val versionlessPayload = GlassesHubCapabilitiesContract.toJson(
            GlassesHubCapabilitiesContract.create(
                features = parsed.features,
                imageSurfaceVersion = parsed.imageSurfaceVersion,
                maxImageBytes = parsed.maxImageBytes,
                versionName = null,
            ),
        )

        assertNull(parsed.versionName)
        assertFalse(parsed.setupComplete)
        assertEquals(0, parsed.pinSurfaceVersion)
        assertEquals(0, parsed.activitySurfaceVersion)
        assertFalse(versionlessPayload.has("versionName"))
        assertFalse(versionlessPayload.getBoolean("setupComplete"))
    }
}
