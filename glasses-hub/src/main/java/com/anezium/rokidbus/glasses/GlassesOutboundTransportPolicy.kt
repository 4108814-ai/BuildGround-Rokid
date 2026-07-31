package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusConstants

internal enum class GlassesOutboundTransport {
    SPP,
    CXR,
}

/**
 * Which link a message leaves the glasses on, and in what order.
 *
 * Small control messages go over CXR first, and that ordering is the point
 * rather than an accident. SPP is one RFCOMM channel whose writes are
 * serialised behind a single lock, and it is also the channel photo sync moves
 * megabytes over — so a control message queued there waits out whatever chunk
 * is mid-flight. CXR is a separate path with a size ceiling that only control
 * messages fit under, which is exactly what keeps them out of that queue.
 *
 * SPP still catches everything CXR cannot take or fails to take: payloads over
 * the ceiling, a CXR link that is down, and a send CXR refuses outright. What it
 * cannot catch is a CXR send that reports success without delivering — that
 * needs an acknowledgement worth believing, not a different running order.
 */
internal object GlassesOutboundTransportPolicy {
    fun order(
        sppConnected: Boolean,
        cxrUp: Boolean,
        payloadBytes: Int,
    ): List<GlassesOutboundTransport> = buildList {
        if (cxrUp && payloadBytes <= BusConstants.CXR_CONTROL_MAX_BYTES) {
            add(GlassesOutboundTransport.CXR)
        }
        if (sppConnected) add(GlassesOutboundTransport.SPP)
    }
}
