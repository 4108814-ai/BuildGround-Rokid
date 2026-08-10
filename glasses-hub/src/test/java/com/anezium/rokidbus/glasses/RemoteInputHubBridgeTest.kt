package com.anezium.rokidbus.glasses

import android.text.InputType
import com.anezium.rokidbus.shared.RemoteNavigationAction as WireNavigationAction
import com.anezium.rokidbus.shared.RemoteNavigationResult as WireNavigationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputHubBridgeTest {
    @Test
    fun `navigation request is reserved before its asynchronous effect completes`() {
        val cache = RemoteNavigationReplayCache(maximumEntries = 4)
        val requestId = "request-12345678"

        assertEquals(RemoteNavigationReplay.New, cache.reserve(requestId))
        assertEquals(RemoteNavigationReplay.InFlight, cache.reserve(requestId))

        val result = WireNavigationResult(
            requestId = requestId,
            action = WireNavigationAction.SELECT,
        )
        cache.complete(result)
        assertEquals(RemoteNavigationReplay.Completed(result), cache.reserve(requestId))
    }

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
