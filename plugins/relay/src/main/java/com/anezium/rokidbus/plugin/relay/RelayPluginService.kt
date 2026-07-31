package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/** Descriptor host only. Part A has no launchable inbox surface. */
class RelayPluginService : NexusPluginService() {
    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit
    override fun onNexusInput(event: NexusInputEvent) = Unit
}
