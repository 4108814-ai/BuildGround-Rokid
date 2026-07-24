package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusPluginClientTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var connected = false
        var closeCount = 0
        var featureBits = 0
        val sends = mutableListOf<Pair<String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
            connected = true
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities(): Int = featureBits
        override fun close() { closeCount += 1 }
    }

    private class RecordingCallbacks : NexusPluginCallbacks {
        val events = mutableListOf<String>()
        override fun onOpen() { events += "open" }
        override fun onClose() { events += "close" }
        override fun onInput(event: NexusInputEvent) { events += "input:${event.keyCode}" }
        override fun onLinkState(state: Int) { events += "link:$state" }
        override fun onGlassesAiButton(active: Boolean) { events += "ai:$active" }
        override fun onRegistrationState(result: Int) { events += "registration:$result" }
        override fun onMessage(path: String, id: String, payload: JSONObject) { events += "message:$path" }
    }

    private fun fixture(): Triple<NexusPluginClient, FakeTransport, RecordingCallbacks> {
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        return Triple(client, transport, callbacks)
    }

    private fun payload() = JSONObject().put("pluginId", "hello")

    @Test
    fun `cold start connects without a static factory`() {
        val (client, transport, _) = fixture()
        assertTrue(transport.connected)
        client.close()
    }

    @Test
    fun `approved pending and denied states gate sends`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.PENDING_USER_APPROVAL)
        assertFalse(client.send("/surface/show", "1", JSONObject()))
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        assertTrue(client.send("/surface/show", "2", JSONObject()))
        transport.listener.onRegistrationState(PluginRegistrationResult.DENIED)
        assertFalse(client.send("/surface/show", "3", JSONObject()))
        assertEquals(1, transport.sends.size)
        assertEquals(
            listOf("registration:1", "registration:0", "registration:2"),
            callbacks.events,
        )
    }

    @Test
    fun `duplicate lifecycle events are idempotent and ordered`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_INPUT, "input-1", payload().put("keyCode", 22).put("action", 0))
        transport.listener.onMessage(BusPaths.PLUGIN_CLOSE, "close-1", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_CLOSE, "close-1", payload())
        assertEquals(listOf("registration:0", "open", "input:22", "close"), callbacks.events)
        client.close()
    }

    @Test
    fun `a fresh open while already open re-presents instead of being swallowed`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        // The hub re-delivers PLUGIN_OPEN when the launcher relaunches an open plugin:
        // the plugin must reset and re-show, not drop it as a duplicate.
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-2", payload())
        transport.listener.onMessage(BusPaths.PLUGIN_INPUT, "input-1", payload().put("keyCode", 22).put("action", 0))
        assertEquals(listOf("registration:0", "open", "open", "input:22"), callbacks.events)
        client.close()
    }

    @Test
    fun `re-registration closes a stale open so the next open dispatches`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "reg-1",
            payload().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"),
        )
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        // The hub restarts and re-accepts this still-running client: opened must reset.
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "reg-2",
            payload().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"),
        )
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-2", payload())
        assertEquals(
            listOf("registration:0", "open", "close", "registration:0", "open"),
            callbacks.events,
        )
        client.close()
    }

    @Test
    fun `close cleans open lifecycle and transport once`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(BusPaths.PLUGIN_OPEN, "open-1", payload())
        client.close()
        client.close()
        assertEquals(listOf("registration:0", "open", "close"), callbacks.events)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `approved plugin private messages reach the service callback`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage("/plugin/hello/migration", "m1", payload().put("future", true))
        assertEquals(
            listOf("registration:0", "message:/plugin/hello/migration"),
            callbacks.events,
        )
        client.close()
    }

    @Test
    fun `glasses AI button start and stop reach the service callback`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onGlassesAiButton(true)
        transport.listener.onGlassesAiButton(false)

        assertEquals(
            listOf("registration:0", "ai:true", "ai:false"),
            callbacks.events,
        )
        client.close()
    }

    @Test
    fun `approved plugin receives glasses device info through the raw message hook`() {
        val (client, transport, callbacks) = fixture()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onMessage(
            BusPaths.GLASSES_DEVICE_INFO,
            "device-1",
            payload().put("batteryLevel", 87),
        )

        assertEquals(
            listOf("registration:0", "message:/glasses/device-info"),
            callbacks.events,
        )
        client.close()
    }

    @Test
    fun `pin show and hide use the client scoped paths and capped payload`() {
        val (client, transport, _) = fixture()
        transport.featureBits = BusCapabilityBits.PIN_SURFACE
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "pin-registration",
            payload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "surfaces"),
        )
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)

        assertEquals(
            NexusSdkResult.SENT,
            client.showPin(
                NexusPin(
                    title = "  NEXUS PIN ",
                    lines = listOf(" sample overlay "),
                    ttlMs = 1L,
                ),
            ),
        )
        assertEquals(NexusSdkResult.SENT, client.hidePin())

        val shown = transport.sends[0]
        assertEquals(BusPaths.PIN_SHOW, shown.first)
        assertEquals(PinSurfaceContract.LOCAL_SURFACE_ID, shown.second.getString("surfaceId"))
        assertEquals("NEXUS PIN", shown.second.getString("title"))
        assertEquals("sample overlay", shown.second.getJSONArray("lines").getString(0))
        assertEquals(PinSurfaceContract.MIN_TTL_MS, shown.second.getLong("ttlMs"))
        assertEquals(BusPaths.PIN_HIDE, transport.sends[1].first)
    }

    @Test
    fun `pin calls require approval grant live spp and feature bit`() {
        val (unapproved, _, _) = fixture()
        assertEquals(NexusSdkResult.NOT_REGISTERED, unapproved.showPin(NexusPin(title = "pin")))

        val (client, transport, _) = fixture()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "pin-no-grant",
            payload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "http_proxy"),
        )
        transport.featureBits = BusCapabilityBits.PIN_SURFACE
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, client.hidePin())

        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "pin-granted",
            payload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "surfaces"),
        )
        transport.featureBits = 0
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.showPin(NexusPin(title = "pin")))

        transport.featureBits = BusCapabilityBits.PIN_SURFACE
        transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.hidePin())
    }

    @Test
    fun `pin model enforces title line and content caps`() {
        assertThrows(IllegalArgumentException::class.java) {
            NexusPin(title = "x".repeat(PinSurfaceContract.MAX_TITLE_CHARS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusPin(lines = listOf("x".repeat(PinSurfaceContract.MAX_LINE_CHARS + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusPin(lines = listOf("a", "b", "c"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusPin(title = " ", lines = listOf(" "))
        }
    }
}
