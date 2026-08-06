package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneHubCapabilitiesContractTest {
    @Test
    fun `approved camera consumer name survives round trip`() {
        val capabilities = PhoneHubCapabilitiesContract.create(
            BusCapabilityBits.CAMERA_CONSUMER_READY,
            " Lens ",
        )
        val payload = PhoneHubCapabilitiesContract.toJson(capabilities)
        val parsed = PhoneHubCapabilitiesContract.parse(payload)

        assertEquals("Lens", parsed.cameraConsumerName)
        assertEquals("Lens", payload.getString("cameraConsumerName"))
    }

    @Test
    fun `legacy and unavailable consumers omit camera consumer name`() {
        val legacy = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))
        val unready = PhoneHubCapabilitiesContract.create(0, "Lens")

        assertNull(legacy.cameraConsumerName)
        assertNull(unready.cameraConsumerName)
        assertFalse(PhoneHubCapabilitiesContract.toJson(unready).has("cameraConsumerName"))
    }

    @Test
    fun `legacy capabilities key remains accepted`() {
        val parsed = PhoneHubCapabilitiesContract.parse(
            JSONObject()
                .put("capabilities", BusCapabilityBits.CAMERA_CONSUMER_READY)
                .put("cameraConsumerName", "Lens"),
        )

        assertEquals(BusCapabilityBits.CAMERA_CONSUMER_READY, parsed.features)
        assertEquals("Lens", parsed.cameraConsumerName)
    }

    @Test
    fun `activity form-factor preference is additive and defaults collapsed`() {
        val legacy = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))
        assertFalse(legacy.activityAlwaysExpanded)

        val payload = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = 0,
                cameraConsumerName = null,
                activityAlwaysExpanded = true,
            ),
        )
        assertEquals(true, payload.getBoolean("activityAlwaysExpanded"))
        assertEquals(
            true,
            PhoneHubCapabilitiesContract.parse(payload).activityAlwaysExpanded,
        )
    }

    @Test
    fun `hud top inset survives round trip`() {
        val payload = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = 0,
                cameraConsumerName = null,
                hudTopInsetDp = 100,
            ),
        )

        assertEquals(100, payload.getInt("hudTopInsetDp"))
        assertEquals(100, PhoneHubCapabilitiesContract.parse(payload).hudTopInsetDp)
    }

    @Test
    fun `hud top inset defaults to zero`() {
        val parsed = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))

        assertEquals(0, parsed.hudTopInsetDp)
    }

    @Test
    fun `hud top inset clamps into the supported range`() {
        assertEquals(
            0,
            PhoneHubCapabilitiesContract.parse(
                JSONObject().put("hudTopInsetDp", -1),
            ).hudTopInsetDp,
        )
        assertEquals(
            240,
            PhoneHubCapabilitiesContract.parse(
                JSONObject().put("hudTopInsetDp", 241),
            ).hudTopInsetDp,
        )
    }

    @Test
    fun `hud top inset rejects garbage wire values`() {
        listOf("100", 100.5, true, JSONObject.NULL).forEach { garbage ->
            assertEquals(
                0,
                PhoneHubCapabilitiesContract.parse(
                    JSONObject().put("hudTopInsetDp", garbage),
                ).hudTopInsetDp,
            )
        }
    }
}
