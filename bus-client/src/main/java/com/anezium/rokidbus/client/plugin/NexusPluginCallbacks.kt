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

    /**
     * The wearer answered this plugin's interactive notice. Arrives whether or
     * not the plugin has a surface open, which is what the notice tier exists
     * for: a plugin can be dormant, say one thing, and be answered.
     *
     * Back is never delivered here. It dismisses the band, always, and a plugin
     * cannot take it.
     */
    fun onNoticeInput(event: NexusInputEvent) = Unit

    /**
     * The wearer picked one of this plugin's notice actions, by its id.
     *
     * Fires instead of [onNoticeInput], never alongside it: a band that offers
     * answers is answered by which one was chosen, and a band that offers none
     * keeps the single confirming gesture. Back is still never delivered to
     * either -- it dismisses the band, and no plugin can take it.
     */
    fun onNoticeAction(id: String) = Unit

    /** The wearer fired one of this plugin's current activity actions. */
    fun onActivityAction(id: String) = Unit

    /**
     * This plugin's activity ended. Reasons are strings so a newer hub can add
     * one without an older SDK silently dropping the callback.
     */
    fun onActivityClosed(reason: String) = Unit

    fun onRegistrationState(result: Int)
    fun onMessage(path: String, id: String, payload: JSONObject) = Unit
    fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = Unit
}
