package com.anezium.rokidbus.plugin.wirelessadb

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbContract
import com.anezium.rokidbus.shared.WirelessAdbReply
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessAdbRuntimeTest {
    @Test
    fun `pairing reply creates copyable commands without persisting the secret`() {
        val host = RecordingHost()
        val runtime = WirelessAdbRuntime(host)
        val now = System.currentTimeMillis()
        runtime.onConnected()
        val status = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            status.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )

        assertTrue(runtime.startPairing())
        val pairing = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            pairing.id,
            WirelessAdbContract.reply(
                "wirelessadb",
                WirelessAdbReply(
                    action = WirelessAdbAction.START_PAIRING,
                    success = true,
                    wifiConnected = true,
                    enabled = true,
                    pairingActive = true,
                    host = "192.0.2.8",
                    connectPort = 37001,
                    pairingPort = 38002,
                    pairingCode = "654321",
                    expiresAtMillis = now + 20_000L,
                ),
            ),
        )

        val active = runtime.snapshot(nowMillis = now + 10_000L)
        assertEquals("adb pair 192.0.2.8:38002 654321", active.commands?.pair)
        assertEquals("adb connect 192.0.2.8:37001", active.commands?.connect)
        assertNull(runtime.snapshot(nowMillis = now + 20_001L).commands)
    }

    @Test
    fun `runtime serializes requests and surfaces transport failure`() {
        val host = RecordingHost(accept = false)
        val runtime = WirelessAdbRuntime(host)
        runtime.onConnected()

        assertNull(runtime.snapshot().busyAction)
        assertEquals("The request could not reach Rokid Nexus.", runtime.snapshot().error)
    }

    @Test
    fun `unanswered request times out and can be retried`() {
        val host = RecordingHost()
        val runtime = WirelessAdbRuntime(host)
        runtime.onConnected()

        assertEquals(WirelessAdbAction.STATUS, runtime.snapshot().busyAction)
        val timedOut = runtime.snapshot(nowMillis = System.currentTimeMillis() + 50_000L)
        assertNull(timedOut.busyAction)
        assertEquals("The glasses did not answer in time.", timedOut.error)
        assertTrue(runtime.refresh())
    }

    @Test
    fun `background refresh does not publish unchanged or transient state`() {
        val host = RecordingHost()
        val runtime = WirelessAdbRuntime(host)
        runtime.onConnected()
        val initialStatus = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            initialStatus.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )
        val observed = mutableListOf<WirelessAdbUiState>()
        runtime.observe(observed::add)
        observed.clear()

        assertTrue(runtime.refreshInBackground())
        assertNull(runtime.snapshot().busyAction)
        assertTrue(observed.isEmpty())

        val refresh = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            refresh.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )

        assertTrue(observed.isEmpty())
    }

    @Test
    fun `user action supersedes an in-flight background refresh`() {
        val host = RecordingHost()
        val runtime = WirelessAdbRuntime(host)
        runtime.onConnected()
        val initialStatus = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            initialStatus.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )

        assertTrue(runtime.refreshInBackground())
        val backgroundRefresh = host.requests.removeFirst()
        assertTrue(runtime.startPairing())
        val pairing = host.requests.removeFirst()
        assertEquals(WirelessAdbAction.START_PAIRING, runtime.snapshot().busyAction)

        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            backgroundRefresh.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )
        assertEquals(WirelessAdbAction.START_PAIRING, runtime.snapshot().busyAction)

        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            pairing.id,
            WirelessAdbContract.reply(
                "wirelessadb",
                WirelessAdbReply(
                    action = WirelessAdbAction.START_PAIRING,
                    success = true,
                    wifiConnected = true,
                    enabled = true,
                    pairingActive = true,
                    host = "192.0.2.8",
                    connectPort = 37001,
                    pairingPort = 38002,
                    pairingCode = "654321",
                    expiresAtMillis = System.currentTimeMillis() + 20_000L,
                ),
            ),
        )
        assertNull(runtime.snapshot().busyAction)
    }

    @Test
    fun `background refresh publishes a real status change`() {
        val host = RecordingHost()
        val runtime = WirelessAdbRuntime(host)
        runtime.onConnected()
        val initialStatus = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            initialStatus.id,
            reply(WirelessAdbAction.STATUS, enabled = false),
        )
        val observed = mutableListOf<WirelessAdbUiState>()
        runtime.observe(observed::add)
        observed.clear()

        assertTrue(runtime.refreshInBackground())
        val refresh = host.requests.removeFirst()
        runtime.onMessage(
            BusPaths.WIRELESS_ADB_REPLY,
            refresh.id,
            reply(WirelessAdbAction.STATUS, enabled = true),
        )

        assertEquals(1, observed.size)
        assertTrue(observed.single().enabled)
    }

    private fun reply(action: WirelessAdbAction, enabled: Boolean): JSONObject =
        WirelessAdbContract.reply(
            "wirelessadb",
            WirelessAdbReply(
                action = action,
                success = true,
                wifiConnected = true,
                enabled = enabled,
                pairingActive = false,
            ),
        )

    private data class Request(val path: String, val id: String, val payload: JSONObject)

    private class RecordingHost(private val accept: Boolean = true) : WirelessAdbHost {
        val requests = ArrayDeque<Request>()

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            requests += Request(path, id, payload)
            return accept
        }
    }
}
