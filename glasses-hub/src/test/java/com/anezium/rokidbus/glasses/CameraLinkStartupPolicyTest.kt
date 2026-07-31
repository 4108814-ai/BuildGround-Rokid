package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusCapabilityBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkStartupPolicyTest {
    @Test
    fun `only a positively advertised LOHS requirement skips initial P2P`() {
        assertEquals(
            CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
            CameraLinkStartupModePolicy.select(BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED),
        )
        assertEquals(CameraLinkStartupMode.P2P_FIRST, CameraLinkStartupModePolicy.select(0))
        assertEquals(
            CameraLinkStartupMode.P2P_FIRST,
            CameraLinkStartupModePolicy.select(BusCapabilityBits.CAMERA_CONSUMER_READY),
        )
        assertEquals(
            CameraLinkStartupMode.P2P_FIRST,
            CameraLinkStartupModePolicy.select(BusCapabilityBits.PHONE_ASSISTED_SETUP),
        )
    }

    @Test
    fun `new glasses retain assisted setup and supported camera signals only`() {
        val advertised = BusCapabilityBits.CAMERA_CONSUMER_READY or
            BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED or
            BusCapabilityBits.PHONE_ASSISTED_SETUP or
            (1 shl 30)

        assertEquals(
            BusCapabilityBits.CAMERA_CONSUMER_READY or
                BusCapabilityBits.CAMERA_LOHS_REVERSE_REQUIRED or
                BusCapabilityBits.PHONE_ASSISTED_SETUP,
            supportedPhoneCapabilities(advertised),
        )
    }

    @Test
    fun `old phone without assisted bit keeps new glasses on manual fallback`() {
        assertFalse(supportsPhoneAssistedSetup(0))
        assertTrue(supportsPhoneAssistedSetup(BusCapabilityBits.PHONE_ASSISTED_SETUP))
    }

    @Test
    fun `missing reverse offer starts P2P only after the bounded wait`() {
        val policy = CameraLinkReverseOfferFallbackPolicy(timeoutMs = 3_000L)

        assertEquals(3_000L, policy.timeoutMs)
        assertTrue(
            policy.shouldStartP2p(
                CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
                reverseOfferAccepted = false,
            ),
        )
        assertFalse(
            policy.shouldStartP2p(
                CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE,
                reverseOfferAccepted = true,
            ),
        )
        assertFalse(
            policy.shouldStartP2p(
                CameraLinkStartupMode.P2P_FIRST,
                reverseOfferAccepted = false,
            ),
        )
    }
}
