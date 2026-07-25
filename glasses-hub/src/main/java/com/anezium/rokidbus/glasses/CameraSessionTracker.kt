package com.anezium.rokidbus.glasses

/**
 * Mirrors the `:camera` process' session state into the main hub process.
 *
 * `CameraActivity` and `CameraLink` are unreachable from here by design (separate process,
 * separate statics), so the main process learns about a live camera session the only way it
 * can: by watching the `/camera/session/state` envelopes that already pass through
 * [GlassesHub.routeLocal] on their way to the phone. Photo sync consults this before touching
 * Wi-Fi Direct.
 */
class CameraSessionTracker(private val onChanged: (Boolean) -> Unit = {}) {
    private var activeSessionId: String? = null

    @Synchronized
    fun isActive(): Boolean = activeSessionId != null

    /**
     * Applies one `/camera/session/state` payload. Returns true when the active/idle edge moved.
     * Unknown states and blank session ids are ignored rather than guessed at: a missed close is
     * recovered by [reset] when the camera process dies.
     */
    @Synchronized
    fun onSessionState(sessionId: String, state: String): Boolean {
        if (sessionId.isBlank()) return false
        val wasActive = activeSessionId != null
        when (state) {
            STATE_OPENED -> activeSessionId = sessionId
            STATE_CLOSED -> if (activeSessionId == sessionId) activeSessionId = null
            else -> return false
        }
        val nowActive = activeSessionId != null
        if (wasActive == nowActive) return false
        onChanged(nowActive)
        return true
    }

    @Synchronized
    fun reset(): Boolean {
        if (activeSessionId == null) return false
        activeSessionId = null
        onChanged(false)
        return true
    }

    companion object {
        const val STATE_OPENED = "opened"
        const val STATE_CLOSED = "closed"
    }
}
