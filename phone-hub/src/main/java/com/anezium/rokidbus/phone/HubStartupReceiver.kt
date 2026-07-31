package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores the user-enabled connected-device hub after a reboot or in-place app update. */
class HubStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!HubStartupPolicy.shouldStart(
                action = intent.action,
                hubEnabled = BusHubService.isEnabled(appContext),
                hasSavedAuthorization = BusHubService.hasSavedAuthorization(appContext),
                canRunHub = BusHubService.canRunHub(appContext),
            )
        ) {
            return
        }
        runCatching { BusHubService.start(appContext) }
            .onFailure { failure ->
                Log.w(TAG, "Hub automatic restart rejected action=${intent.action}", failure)
            }
    }

    private companion object {
        const val TAG = "ROKIDBUS-PHONE"
    }
}

/** Pure startup gate so boot behavior stays covered without starting Android services in a test. */
object HubStartupPolicy {
    fun shouldStart(
        action: String?,
        hubEnabled: Boolean,
        hasSavedAuthorization: Boolean,
        canRunHub: Boolean,
    ): Boolean = action in SUPPORTED_ACTIONS &&
        hubEnabled &&
        hasSavedAuthorization &&
        canRunHub

    private val SUPPORTED_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
    )
}
