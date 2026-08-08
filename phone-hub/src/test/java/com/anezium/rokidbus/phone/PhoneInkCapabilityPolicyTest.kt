package com.anezium.rokidbus.phone

import com.anezium.rokidbus.ink.InkWire
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LinkStateBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInkCapabilityPolicyTest {
    @Test
    fun `protocol bit version and spp all gate ink availability`() {
        val advertised = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.INK_SURFACE,
            imageSurfaceVersion = 0,
            inkSurfaceVersion = InkWire.VERSION,
            maxImageBytes = 0,
            versionName = null,
        )
        val accepted = PhoneInkCapabilityPolicy.acceptedVersion(advertised)

        assertEquals(InkWire.VERSION, accepted)
        assertFalse(PhoneInkCapabilityPolicy.isAvailable(accepted, LinkStateBits.CXR_CONTROL_UP))
        assertTrue(PhoneInkCapabilityPolicy.isAvailable(accepted, LinkStateBits.SPP_DATA_UP))
        assertEquals(
            0,
            PhoneInkCapabilityPolicy.acceptedVersion(
                advertised.copy(inkSurfaceVersion = InkWire.VERSION + 1),
            ),
        )
        assertEquals(
            0,
            PhoneInkCapabilityPolicy.acceptedVersion(advertised.copy(features = 0)),
        )
        assertEquals(
            0,
            PhoneInkCapabilityPolicy.acceptedVersion(advertised.copy(protocolVersion = 0)),
        )
    }
}
