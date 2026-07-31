package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusConstants

internal enum class GlassesOutboundTransport {
    SPP,
    CXR,
}

/**
 * Which link a message leaves the glasses on, and in what order.
 *
 * SPP first, and only then CXR. That is not the tidy answer: SPP is one RFCOMM
 * channel whose writes are serialised behind a single lock, and it is the
 * channel photo sync moves megabytes over, so a control message sent that way
 * can wait out whatever chunk is mid-flight. Sending control traffic on the
 * separate CXR path avoids that queue entirely, which is why this file used to
 * prefer it.
 *
 * It prefers SPP anyway, because the glasses-to-phone CXR path does not
 * currently arrive. Measured on Hi Rokid Global G1.11.11.0727: the glasses send,
 * `CXR-S TX … result=0` reports success, the phone's Rokid app logs the frame
 * with its full payload and answers RESPONSE_SUCCEED — and never hands it to the
 * bound third-party client, which sees nothing at all. Phone-to-glasses over the
 * same link is unaffected, so this ordering is deliberately asymmetric: the two
 * directions do not have the same reliability and cannot have the same policy.
 *
 * Queueing behind a photo sync chunk costs milliseconds. Choosing a path that
 * silently drops the message costs the feature. Until an acknowledgement exists
 * that can tell those two apart at runtime, the ordering carries the knowledge.
 */
internal object GlassesOutboundTransportPolicy {
    fun order(
        sppConnected: Boolean,
        cxrUp: Boolean,
        payloadBytes: Int,
    ): List<GlassesOutboundTransport> = buildList {
        if (sppConnected) add(GlassesOutboundTransport.SPP)
        if (cxrUp && payloadBytes <= BusConstants.CXR_CONTROL_MAX_BYTES) {
            add(GlassesOutboundTransport.CXR)
        }
    }
}
