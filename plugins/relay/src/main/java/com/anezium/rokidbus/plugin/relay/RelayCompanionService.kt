package com.anezium.rokidbus.plugin.relay

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
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
    override fun onCreate() {
        super.onCreate()
        RelayDiagnostics.recordCompanionServiceBound(this, bound = true)
        RelaySettingsActivity.notifyDataChanged()
    }

    override fun onDestroy() {
        RelayDiagnostics.recordCompanionServiceBound(this, bound = false)
        RelaySettingsActivity.notifyDataChanged()
        super.onDestroy()
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
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

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        logAppeared()
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        logDisappeared()
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceAppeared(address: String) = logAppeared()

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceDisappeared(address: String) = logDisappeared()

    private fun logAppeared() {
        RelayDiagnostics.recordCompanionPresence(this, appeared = true)
        RelayGuardianService.requestImmediateHealthEvaluation()
        RelaySettingsActivity.notifyDataChanged()
        Log.i(TAG, "companion device appeared")
    }

    private fun logDisappeared() {
        RelayDiagnostics.recordCompanionPresence(this, appeared = false)
        RelayGuardianService.requestImmediateHealthEvaluation()
        RelaySettingsActivity.notifyDataChanged()
        Log.i(TAG, "companion device disappeared")
    }

    private companion object {
        const val TAG = "RelayCompanion"
    }
}
