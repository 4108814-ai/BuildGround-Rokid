package com.anezium.rokidbus.phone

import android.content.Intent

/**
 * In-process hand-off for pointer commands.
 *
 * Everything else on this screen is a broadcast, which is right for events that
 * happen when a field gains focus or a key is pressed. A dragging finger is not
 * that: it produces an event per touch sample, and routing each one through the
 * system's broadcast queue adds jitter you can see as a stuttering cursor. The
 * activity and the hub service live in the same process, so movement takes the
 * direct path and falls back to the broadcast only when no hub is listening.
 */
internal object PhonePointerChannel {
    @Volatile
    private var handler: ((Intent) -> Unit)? = null

    fun setHandler(next: ((Intent) -> Unit)?) {
        handler = next
    }

    /** True when the command was delivered in-process; false asks the caller to broadcast. */
    fun deliver(intent: Intent): Boolean {
        val target = handler ?: return false
        target(intent)
        return true
    }
}
