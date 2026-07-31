package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesOutboundTransportPolicyTest {
    @Test
    fun `a control message takes CXR first, keeping it out of the bulk queue`() {
        assertEquals(
            listOf(GlassesOutboundTransport.CXR, GlassesOutboundTransport.SPP),
            GlassesOutboundTransportPolicy.order(
                sppConnected = true,
                cxrUp = true,
                payloadBytes = 128,
            ),
        )
    }

    @Test
    fun `SPP still backs up a control message when CXR is down`() {
        assertEquals(
            listOf(GlassesOutboundTransport.SPP),
            GlassesOutboundTransportPolicy.order(
                sppConnected = true,
                cxrUp = false,
                payloadBytes = 128,
            ),
        )
    }

    @Test
    fun `CXR carries small control messages when SPP is unavailable`() {
        assertEquals(
            listOf(GlassesOutboundTransport.CXR),
            GlassesOutboundTransportPolicy.order(
                sppConnected = false,
                cxrUp = true,
                payloadBytes = 128,
            ),
        )
    }

    @Test
    fun `oversized messages use only SPP`() {
        assertEquals(
            listOf(GlassesOutboundTransport.SPP),
            GlassesOutboundTransportPolicy.order(
                sppConnected = true,
                cxrUp = true,
                payloadBytes = BusConstants.CXR_CONTROL_MAX_BYTES + 1,
            ),
        )
    }

    @Test
    fun `no transport is selected when neither is usable`() {
        assertEquals(
            emptyList<GlassesOutboundTransport>(),
            GlassesOutboundTransportPolicy.order(
                sppConnected = false,
                cxrUp = false,
                payloadBytes = 128,
            ),
        )
    }
}
