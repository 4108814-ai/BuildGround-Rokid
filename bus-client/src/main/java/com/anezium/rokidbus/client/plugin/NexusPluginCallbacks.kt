package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject

interface NexusPluginCallbacks {
    fun onOpen()
    fun onClose()
    fun onInput(event: NexusInputEvent)
    fun onLinkState(state: Int)
    fun onGlassesAiButton(active: Boolean) = Unit

    /**
     * The notice this plugin raised is no longer visible, and why. Delivered
     * once per notice, including when this plugin hid it itself, so a plugin
     * has exactly one place to clean up whatever the banner was standing for.
     *
     * Not delivered when the plugin is what disappeared.
     */
    fun onNoticeClosed(reason: NexusNoticeCloseReason) = Unit
    fun onRegistrationState(result: Int)
    fun onMessage(path: String, id: String, payload: JSONObject) = Unit
    fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = Unit
}
