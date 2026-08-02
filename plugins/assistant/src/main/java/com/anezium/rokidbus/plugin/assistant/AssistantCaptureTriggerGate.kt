package com.anezium.rokidbus.plugin.assistant

/**
 * Claims one capture start for each physical button edge or gesture-open id. The capture state
 * remains a second guard in the service so a button callback racing a gesture-open cannot start a
 * second speech session.
 */
internal class AssistantCaptureTriggerGate {
    private var buttonActive = false
    private var startClaimed = false
    private var lastGestureOpenId: String? = null

    fun claimButtonStart(): Boolean {
        if (buttonActive) return false
        buttonActive = true
        return claimStart()
    }

    fun onButtonStop() {
        buttonActive = false
        startClaimed = false
    }

    fun claimGestureOpen(gestureId: String): Boolean {
        if (gestureId.isBlank() || gestureId == lastGestureOpenId) return false
        lastGestureOpenId = gestureId
        if (!buttonActive) startClaimed = false
        return claimStart()
    }

    fun resetSession() {
        buttonActive = false
        startClaimed = false
    }

    private fun claimStart(): Boolean {
        if (startClaimed) return false
        startClaimed = true
        return true
    }
}
