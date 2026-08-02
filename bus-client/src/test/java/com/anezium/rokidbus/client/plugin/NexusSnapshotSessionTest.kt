package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusSnapshotSessionTest {
    private data class Sent(val path: String, val id: String, val payload: JSONObject)

    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Sent>()

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += Sent(path, id, JSONObject(payload.toString()))
            return true
        }

        override fun sendBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean = true

        override fun capabilities(): Int = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private class PluginCallbacks : NexusPluginCallbacks {
        val generic = mutableListOf<String>()
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onMessage(path: String, id: String, payload: JSONObject) {
            generic += path
        }
        override fun onBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ) {
            generic += path
        }
    }

    private class SnapshotCallbacks : NexusSnapshotCallbacks {
        val images = mutableListOf<ByteArray>()
        val errors = mutableListOf<NexusSnapshotError>()
        override fun onSnapshotCaptured(jpeg: ByteArray) {
            images += jpeg
        }
        override fun onSnapshotError(error: NexusSnapshotError) {
            errors += error
        }
    }

    private data class Fixture(
        val client: NexusPluginClient,
        val transport: FakeTransport,
        val pluginCallbacks: PluginCallbacks,
    )

    private fun fixture(capabilities: String = "camera"): Fixture {
        val transport = FakeTransport()
        val pluginCallbacks = PluginCallbacks()
        val client = NexusPluginClient("hello", pluginCallbacks, transport)
        client.connect()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities),
        )
        return Fixture(client, transport, pluginCallbacks)
    }

    @Test
    fun `capture routes one targeted JPEG and can be reused`() {
        val fixture = fixture()
        val callbacks = SnapshotCallbacks()
        val session = fixture.client.snapshotSession(callbacks)

        assertEquals(NexusSdkResult.SENT, session.capture())
        assertTrue(session.isPending)
        assertEquals(NexusSdkResult.SENT, session.capture())
        assertEquals(1, fixture.transport.sends.size)
        val request = fixture.transport.sends.single()
        assertEquals(BusPaths.CAMERA_SNAPSHOT_REQUEST, request.path)
        assertEquals(request.id, request.payload.getString("requestId"))

        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1)
        fixture.transport.listener.onBinary(
            BusPaths.CAMERA_SNAPSHOT_RESULT,
            request.id,
            JSONObject()
                .put("pluginId", "hello")
                .put("requestId", request.id)
                .put("mimeType", "image/jpeg"),
            jpeg,
        )

        assertFalse(session.isPending)
        assertArrayEquals(jpeg, callbacks.images.single())
        assertTrue(callbacks.errors.isEmpty())
        assertTrue(fixture.pluginCallbacks.generic.isEmpty())
        assertEquals(NexusSdkResult.SENT, session.capture())
        assertEquals(2, fixture.transport.sends.size)
    }

    @Test
    fun `structured errors map and camera grant is required`() {
        val fixture = fixture()
        val callbacks = SnapshotCallbacks()
        val session = fixture.client.snapshotSession(callbacks)
        assertEquals(NexusSdkResult.SENT, session.capture())
        val request = fixture.transport.sends.single()

        fixture.transport.listener.onMessage(
            BusPaths.CAMERA_SNAPSHOT_ERROR,
            request.id,
            JSONObject()
                .put("pluginId", "hello")
                .put("requestId", request.id)
                .put("code", "BUSY"),
        )

        assertEquals(listOf(NexusSnapshotError.BUSY), callbacks.errors)
        assertFalse(session.isPending)
        assertTrue(fixture.pluginCallbacks.generic.isEmpty())

        val withoutGrant = fixture("surfaces")
        val denied = withoutGrant.client.snapshotSession(SnapshotCallbacks())
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, denied.capture())
        assertTrue(withoutGrant.transport.sends.isEmpty())
    }
}
