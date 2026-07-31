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

    /**
     * Whether the wearer is in the inbox, which decides who owns the bus.
     *
     * Relay has two things that talk to the hub — the band runtime, woken by a
     * notification, and this plugin's own service, opened from the menu — and
     * each was creating its own `NexusPluginClient` under the same plugin id.
     * The hub keeps both, since it identifies a registration by callback binder
     * rather than by id, but several paths downstream assume there is one:
     * external lifecycle delivery resolves with `singleOrNull` and finds
     * nothing, outbound sends pick whichever registration matched first, and
     * speech replies follow the binder that was chosen rather than the one that
     * asked. In practice that means opening the inbox while a band is live can
     * fail outright, or dictation from a band can hang on "Listening…" because
     * its transcript went to the other client.
     *
     * So only one is ever connected. The inbox wins while it is open: the
     * wearer is already looking at their messages, and a band over the top of
     * the list would be announcing something they can see.
     */
    @Volatile
    var inboxOpen: Boolean = false
        private set

    fun inboxOpened() {
        inboxOpen = true
        main.post { listener?.suspendBand() }
    }

    fun inboxClosed() {
        inboxOpen = false
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
