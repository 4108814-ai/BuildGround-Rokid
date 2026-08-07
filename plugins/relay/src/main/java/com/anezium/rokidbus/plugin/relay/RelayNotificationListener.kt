package com.anezium.rokidbus.plugin.relay

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RelayNotificationListener : NotificationListenerService() {
    private val runtime by lazy { RelayNoticeRuntime(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControl.attach(this)
        rebuildFromActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        ingest(sbn, mayShow = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        ReplyRepository.forget(sbn)
        // Answering on the phone empties the row here too, while the wearer is
        // looking straight at it.
        NotificationControl.notifyCaptured()
    }

    /**
     * Losing the grant drops what the grant bought.
     *
     * Detaching alone left captured message text and live reply tokens in
     * memory, usable for as long as the inbox kept the process alive — so a
     * wearer could revoke Notification Access and still be shown the message,
     * and still send through the retained PendingIntent. The authority is gone;
     * so is everything it authorised.
     */
    override fun onListenerDisconnected() {
        ReplyRepository.clear()
        NotificationControl.detach(this)
        runtime.shutdown()
        Log.w(TAG, "notification listener disconnected")
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, RelayNotificationListener::class.java))
    }

    override fun onDestroy() {
        NotificationControl.detach(this)
        runtime.shutdown()
        super.onDestroy()
    }

    /** Hands the bus to the inbox: the band's client closes and its notice goes. */
    internal fun suspendBand() {
        runtime.shutdown()
    }

    internal fun refreshFromSettings() {
        if (!RelaySettings(this).enabled()) {
            ReplyRepository.clear()
            runtime.shutdown()
            return
        }
        rebuildFromActiveNotifications()
    }

    private fun rebuildFromActiveNotifications() {
        ReplyRepository.markAllReadOnly()
        if (!RelaySettings(this).enabled() || NotificationForwardingPolicy.isPaused(this)) return
        val count = runCatching {
            activeNotifications.orEmpty().count { sbn -> ingest(sbn, mayShow = false) }
        }.onFailure { failure ->
            Log.w(TAG, "active notification sync failed cause=${failure.javaClass.simpleName}")
        }.getOrDefault(0)
        Log.i(TAG, "active notification sync repliableCount=$count")
    }

    private fun ingest(sbn: StatusBarNotification, mayShow: Boolean): Boolean {
        val settings = RelaySettings(this)
        val source = if (mayShow) "posted" else "rebuild"
        val keyHash = ReplyRepository.stableId(sbn.key).take(8)
        val interactive = NotificationForwardingPolicy.isPhoneScreenOn(this)

        fun finish(decision: String, accepted: Boolean): Boolean {
            Log.i(
                TAG,
                "ingest source=$source keyHash=$keyHash interactive=$interactive decision=$decision",
            )
            return accepted
        }

        if (!settings.enabled()) return finish("disabled", accepted = false)
        if (settings.pauseWhilePhoneScreenOn() && interactive) {
            return finish("paused", accepted = false)
        }

        // Carrying a free-form reply action is the whole admission test: it passes
        // only notifications a human is waiting on an answer to.
        val action = sbn.notification.findRepliableAction()
            ?: return finish("no_action", accepted = false)
        if (!settings.admits()) return finish("no_action", accepted = true)

        val capture = ReplyRepository.capture(this, sbn, action)
            ?: return finish("capture_failed", accepted = false)
        // The inbox, if it is open, is the only thing that will show this: the
        // band stands down while it holds the bus.
        NotificationControl.notifyCaptured()
        val decision = when {
            !capture.contentChanged -> "unchanged"
            !mayShow -> "captured rebuild_only"
            else -> "captured show=${capture.shouldShowNow}"
        }
        val accepted = finish(decision, accepted = true)
        if (mayShow && capture.shouldShowNow) runtime.show(capture.reply)
        return accepted
    }

    private companion object {
        const val TAG = "NexusRelayListener"
    }
}
