package com.anezium.rokidbus.glasses

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTouchpadInputPolicyTest {
    @Test
    fun `backdrop consumes unclaimed confirm and direction downs`() {
        val keys = listOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        keys.forEach { keyCode ->
            assertTrue(
                NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                    claimsAllInput = true,
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                ),
            )
        }
    }

    @Test
    fun `non-backdrop keeps unclaimed classifications passing through`() {
        assertFalse(
            NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = false,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
            ),
        )
        assertFalse(
            NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = false,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
            ),
        )
    }

    @Test
    fun `backdrop fallback leaves contacts back and key ups untouched`() {
        assertFalse(
            NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = true,
                keyCode = TripleTapDetector.KEYCODE_NOTIFICATION,
                action = KeyEvent.ACTION_DOWN,
            ),
        )
        assertFalse(
            NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = true,
                keyCode = KeyEvent.KEYCODE_BACK,
                action = KeyEvent.ACTION_DOWN,
            ),
        )
        assertFalse(
            NoticeTouchpadInputPolicy.consumesUnclaimedKey(
                claimsAllInput = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_UP,
            ),
        )
    }
}
