package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbWifiPreparationTest {
    @Test
    fun `ready network does not touch wifi state`() {
        var wifiStateRead = false
        var enableCalled = false
        var awaitCalled = false

        val result = prepareWifiForWirelessAdb(
            isNetworkReady = { true },
            isWifiEnabled = {
                wifiStateRead = true
                false
            },
            enableWifi = {
                enableCalled = true
                true
            },
            awaitNetworkReady = {
                awaitCalled = true
                true
            },
        )

        assertEquals(WirelessAdbWifiPreparationResult.READY, result)
        assertFalse(wifiStateRead)
        assertFalse(enableCalled)
        assertFalse(awaitCalled)
    }

    @Test
    fun `disabled wifi is enabled and allowed to reconnect`() {
        var enableCalled = false
        var awaitCalled = false

        val result = prepareWifiForWirelessAdb(
            isNetworkReady = { false },
            isWifiEnabled = { false },
            enableWifi = {
                enableCalled = true
                true
            },
            awaitNetworkReady = {
                awaitCalled = true
                true
            },
        )

        assertEquals(WirelessAdbWifiPreparationResult.READY, result)
        assertTrue(enableCalled)
        assertTrue(awaitCalled)
    }

    @Test
    fun `bridge failure is reported without waiting for a network`() {
        var awaitCalled = false

        val result = prepareWifiForWirelessAdb(
            isNetworkReady = { false },
            isWifiEnabled = { false },
            enableWifi = { false },
            awaitNetworkReady = {
                awaitCalled = true
                true
            },
        )

        assertEquals(WirelessAdbWifiPreparationResult.ENABLE_FAILED, result)
        assertFalse(awaitCalled)
    }

    @Test
    fun `enabled wifi without a network is not toggled`() {
        var enableCalled = false
        var awaitCalled = false

        val result = prepareWifiForWirelessAdb(
            isNetworkReady = { false },
            isWifiEnabled = { true },
            enableWifi = {
                enableCalled = true
                true
            },
            awaitNetworkReady = {
                awaitCalled = true
                true
            },
        )

        assertEquals(WirelessAdbWifiPreparationResult.NETWORK_UNAVAILABLE, result)
        assertFalse(enableCalled)
        assertFalse(awaitCalled)
    }

    @Test
    fun `wifi enabled by Nexus must reconnect to a saved network`() {
        val result = prepareWifiForWirelessAdb(
            isNetworkReady = { false },
            isWifiEnabled = { false },
            enableWifi = { true },
            awaitNetworkReady = { false },
        )

        assertEquals(WirelessAdbWifiPreparationResult.NETWORK_UNAVAILABLE, result)
    }
}
