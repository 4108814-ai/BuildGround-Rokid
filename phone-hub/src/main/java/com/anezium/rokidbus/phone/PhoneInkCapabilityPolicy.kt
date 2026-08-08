package com.anezium.rokidbus.phone

import com.anezium.rokidbus.ink.InkWire
import com.anezium.rokidbus.shared.GlassesHubCapabilities
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LinkStateBits

internal object PhoneInkCapabilityPolicy {
    fun acceptedVersion(capabilities: GlassesHubCapabilities): Int =
        if (GlassesHubCapabilitiesContract.supportsInkSurface(capabilities)) InkWire.VERSION else 0

    fun isAvailable(acceptedVersion: Int, linkState: Int): Boolean =
        acceptedVersion == InkWire.VERSION && linkState and LinkStateBits.SPP_DATA_UP != 0
}
