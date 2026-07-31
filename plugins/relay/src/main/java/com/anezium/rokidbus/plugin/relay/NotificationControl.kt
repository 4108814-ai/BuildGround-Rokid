package com.anezium.rokidbus.plugin.relay

import android.os.Handler
import android.os.Looper

internal object NotificationControl {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: RelayNotificationListener? = null

    fun attach(service: RelayNotificationListener) {
        listener = service
    }

    fun detach(service: RelayNotificationListener) {
        if (listener === service) listener = null
    }

    fun refreshFromSettings() {
        main.post { listener?.refreshFromSettings() }
    }

    fun cancelAfterReply(notificationKey: String) {
        if (notificationKey.isBlank()) return
        CANCEL_DELAYS_MS.forEach { delayMs ->
            main.postDelayed({ listener?.cancelNotification(notificationKey) }, delayMs)
        }
    }

    private val CANCEL_DELAYS_MS = longArrayOf(250L, 1_000L, 2_500L)
}
