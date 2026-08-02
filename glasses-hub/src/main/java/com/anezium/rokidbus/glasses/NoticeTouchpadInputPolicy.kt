package com.anezium.rokidbus.glasses

import android.view.KeyEvent

/** Pure fallback claim for classifications a backdrop notice does not handle itself. */
internal object NoticeTouchpadInputPolicy {
    fun consumesUnclaimedKey(
        claimsAllInput: Boolean,
        keyCode: Int,
        action: Int,
    ): Boolean =
        claimsAllInput &&
            action == KeyEvent.ACTION_DOWN &&
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
}
