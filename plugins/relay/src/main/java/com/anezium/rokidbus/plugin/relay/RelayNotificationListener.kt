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
    }

    override fun onListenerDisconnected() {
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

    internal fun refreshFromSettings() {
        if (!RelaySettings(this).enabled()) {
            ReplyRepository.clear()
            runtime.shutdown()
            return
        }
        rebuildFromActiveNotifications()
    }

    private fun rebuildFromActiveNotifications() {
        ReplyRepository.clear()
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
        if (!settings.enabled() || NotificationForwardingPolicy.isPaused(this)) return false

        // Carrying a free-form reply action is the whole admission test: it passes
        // only notifications a human is waiting on an answer to.
        val action = sbn.notification.findRepliableAction() ?: return false
        if (!settings.admits()) return true

        val capture = ReplyRepository.capture(this, sbn, action) ?: return false
        Log.i(
            TAG,
            "captured changed=${capture.shouldShowNow} textChars=${capture.reply.content.renderedText.length} " +
                "imageBytes=${capture.reply.imagePreview?.bytes?.size ?: 0}",
        )
        if (mayShow && capture.shouldShowNow) runtime.show(capture.reply)
        return true
    }

    private companion object {
        const val TAG = "NexusRelayListener"
    }
}
