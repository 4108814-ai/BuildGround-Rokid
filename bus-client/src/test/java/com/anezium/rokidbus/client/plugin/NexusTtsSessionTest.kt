package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusTtsSessionTest {
    private data class Sent(val path: String, val id: String, val payload: JSONObject)

    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Sent>()
        var features = BusCapabilityBits.TTS
        var grants = "tts"
        var sendSucceeds = true

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += Sent(path, id, JSONObject(payload.toString()))
            return sendSucceeds
        }

        override fun sendBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean = false

        override fun capabilities(): Int = features
        override fun approvedCapabilities(): String = grants
        override fun close() = Unit
    }

    private class PluginCallbacks : NexusPluginCallbacks {
        val rawMessages = mutableListOf<String>()
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onMessage(path: String, id: String, payload: JSONObject) {
            rawMessages += path
        }
    }

    private class TtsCallbacks : NexusTtsCallbacks {
        val started = mutableListOf<String>()
        val done = mutableListOf<Pair<String, NexusTtsDoneReason>>()
        override fun onTtsStarted(utteranceId: String) {
            started += utteranceId
        }
        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) {
            done += utteranceId to reason
        }
    }

    private data class Fixture(
        val client: NexusPluginClient,
        val transport: FakeTransport,
        val pluginCallbacks: PluginCallbacks,
    )

    private fun fixture(
        grants: String = "tts",
        features: Int = BusCapabilityBits.TTS,
        linkState: Int = LinkStateBits.CXR_CONTROL_UP,
    ): Fixture {
        val transport = FakeTransport().apply {
            this.grants = grants
            this.features = features
        }
        val pluginCallbacks = PluginCallbacks()
        val client = NexusPluginClient("hello", pluginCallbacks, transport)
        client.connect()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        transport.listener.onLinkState(linkState)
        return Fixture(client, transport, pluginCallbacks)
    }

    @Test
    fun `speak chooses an opaque id normalizes text and routes terminal callbacks once`() {
        val fixture = fixture()
        val callbacks = TtsCallbacks()
        val session = fixture.client.ttsSession(callbacks)

        assertEquals(NexusSdkResult.SENT, session.speak("  hello\nthere  "))
        val sent = fixture.transport.sends.single()
        assertEquals(BusPaths.TTS_SPEAK, sent.path)
        assertEquals("hello there", sent.payload.getString("text"))
        val utteranceId = sent.payload.getString("utteranceId")
        assertTrue(utteranceId.isNotBlank())
        assertTrue(utteranceId.length <= TtsContract.MAX_UTTERANCE_ID_CHARS)

        fixture.transport.listener.onMessage(
            BusPaths.TTS_STARTED,
            "started",
            JSONObject().put("utteranceId", utteranceId),
        )
        fixture.transport.listener.onMessage(
            BusPaths.TTS_STARTED,
            "started-duplicate-shape",
            JSONObject().put("utteranceId", utteranceId),
        )
        fixture.transport.listener.onMessage(
            BusPaths.TTS_DONE,
            "done",
            JSONObject()
                .put("utteranceId", utteranceId)
                .put("reason", "COMPLETED"),
        )
        fixture.transport.listener.onMessage(
            BusPaths.TTS_DONE,
            "done-duplicate-shape",
            JSONObject()
                .put("utteranceId", utteranceId)
                .put("reason", "COMPLETED"),
        )

        assertEquals(listOf(utteranceId), callbacks.started)
        assertEquals(listOf(utteranceId to NexusTtsDoneReason.COMPLETED), callbacks.done)
        assertNull(session.activeUtteranceId)
        assertTrue(fixture.pluginCallbacks.rawMessages.isEmpty())
    }

    @Test
    fun `preempted and completed utterances remain independently correlated`() {
        val fixture = fixture()
        val callbacks = TtsCallbacks()
        val session = fixture.client.ttsSession(callbacks)
        session.speak("one")
        session.speak("two")
        val first = fixture.transport.sends[0].payload.getString("utteranceId")
        val second = fixture.transport.sends[1].payload.getString("utteranceId")

        listOf(first to "PREEMPTED", second to "COMPLETED").forEachIndexed { index, pair ->
            fixture.transport.listener.onMessage(
                BusPaths.TTS_DONE,
                "done-$index",
                JSONObject()
                    .put("utteranceId", pair.first)
                    .put("reason", pair.second),
            )
        }

        assertEquals(
            listOf(
                first to NexusTtsDoneReason.PREEMPTED,
                second to NexusTtsDoneReason.COMPLETED,
            ),
            callbacks.done,
        )
    }

    @Test
    fun `platform cancellation is exposed as CANCELLED`() {
        val fixture = fixture()
        val callbacks = TtsCallbacks()
        val session = fixture.client.ttsSession(callbacks)
        session.speak("hello")
        val utteranceId = session.activeUtteranceId!!

        fixture.transport.listener.onMessage(
            BusPaths.TTS_DONE,
            "cancelled",
            JSONObject()
                .put("utteranceId", utteranceId)
                .put("reason", "CANCELLED"),
        )

        assertEquals(listOf(utteranceId to NexusTtsDoneReason.CANCELLED), callbacks.done)
        assertNull(session.activeUtteranceId)
    }

    @Test
    fun `stop addresses only the SDK current utterance`() {
        val fixture = fixture()
        val callbacks = TtsCallbacks()
        val session = fixture.client.ttsSession(callbacks)
        assertEquals(NexusSdkResult.SENT, session.speak("hello"))
        val utteranceId = session.activeUtteranceId!!

        assertEquals(NexusSdkResult.SENT, session.stop())
        val stop = fixture.transport.sends.last()
        assertEquals(BusPaths.TTS_STOP, stop.path)
        assertEquals(utteranceId, stop.payload.getString("utteranceId"))
        fixture.transport.listener.onMessage(
            BusPaths.TTS_DONE,
            "stopped",
            JSONObject()
                .put("utteranceId", utteranceId)
                .put("reason", "STOPPED"),
        )
        assertEquals(listOf(utteranceId to NexusTtsDoneReason.STOPPED), callbacks.done)
    }

    @Test
    fun `approval capability renderer and payload checks fail before sending`() {
        val unregisteredTransport = FakeTransport()
        val unregistered = NexusPluginClient("hello", PluginCallbacks(), unregisteredTransport)
        unregistered.connect()
        assertEquals(
            NexusSdkResult.NOT_REGISTERED,
            unregistered.ttsSession(TtsCallbacks()).speak("hello"),
        )

        val noGrant = fixture(grants = "surfaces")
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            noGrant.client.ttsSession(TtsCallbacks()).speak("hello"),
        )
        val noRenderer = fixture(features = 0)
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            noRenderer.client.ttsSession(TtsCallbacks()).speak("hello"),
        )
        val valid = fixture()
        assertEquals(
            NexusSdkResult.INVALID_PAYLOAD,
            valid.client.ttsSession(TtsCallbacks()).speak("\n\r"),
        )
        assertTrue(valid.transport.sends.isEmpty())
    }

    @Test
    fun `SDK enforces the same five-command one-second budget`() {
        val fixture = fixture()
        var now = 0L
        val session = NexusTtsSession(fixture.client, TtsCallbacks()) { now }
        repeat(TtsContract.MAX_MESSAGES_PER_SECOND) {
            assertEquals(NexusSdkResult.SENT, session.speak("hello $it"))
        }
        assertEquals(NexusSdkResult.TTS_RATE_LIMITED, session.speak("too much"))
        now = 1_000L
        assertEquals(NexusSdkResult.SENT, session.speak("allowed again"))
    }
}
