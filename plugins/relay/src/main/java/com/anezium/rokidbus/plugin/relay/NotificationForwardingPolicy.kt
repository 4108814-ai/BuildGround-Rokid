package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.os.PowerManager

internal object NotificationForwardingPolicy {
    fun isPaused(context: Context): Boolean =
        RelaySettings(context).pauseWhilePhoneScreenOn() && isPhoneScreenOn(context)

    fun isPhoneScreenOn(context: Context): Boolean {
        val powerManager = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive == true
    }
}
