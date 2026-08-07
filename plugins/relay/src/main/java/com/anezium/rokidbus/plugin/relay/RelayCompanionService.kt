package com.anezium.rokidbus.plugin.relay

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log

/**
 * Delivery target for the presence observation started by [CompanionDeviceCoordinator].
 * It exists only so the callbacks that observation relies on have somewhere to be delivered.
 *
 * Plugins are held to `minSdk 30` while this class extends one that arrived in 31, which
 * lint reads as a class that could load on a system without it. It cannot: the only thing
 * that ever binds this service is presence observation, and [CompanionDeviceCoordinator]
 * refuses to start that below 31 — so on Android 11 nothing here is ever reached.
 */
@Suppress("NewApi")
class RelayCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED,
            -> logAppeared()

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED,
            -> logDisappeared()
        }
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) = logAppeared()

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = logDisappeared()

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceAppeared(address: String) = logAppeared()

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceDisappeared(address: String) = logDisappeared()

    private fun logAppeared() {
        Log.i(TAG, "companion device appeared")
    }

    private fun logDisappeared() {
        Log.i(TAG, "companion device disappeared")
    }

    private companion object {
        const val TAG = "RelayCompanion"
    }
}
