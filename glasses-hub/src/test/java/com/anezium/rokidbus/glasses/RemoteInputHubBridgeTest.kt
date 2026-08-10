package com.anezium.rokidbus.glasses

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputHubBridgeTest {
    @Test
    fun `detects text and number passwords without treating ordinary text as sensitive`() {
        assertTrue(
            RemoteInputHubBridge.isSensitiveInput(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
        assertTrue(
            RemoteInputHubBridge.isSensitiveInput(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertFalse(
            RemoteInputHubBridge.isSensitiveInput(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            ),
        )
    }
}
