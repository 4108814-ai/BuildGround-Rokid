package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusSpeechSessionTest {
    private data class Sent(
        val path: String,
        val id: String,
        val payload: JSONObject,
    )

    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Sent>()
        var closed = false

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

        override fun close() {
            closed = true
        }
    }

    private class PluginCallbacks : NexusPluginCallbacks {
        val genericMessages = mutableListOf<String>()
        var closeCalls = 0

        override fun onOpen() = Unit
        override fun onClose() {
            closeCalls += 1
        }
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onMessage(path: String, id: String, payload: JSONObject) {
            genericMessages += path
        }
        override fun onBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ) {
            genericMessages += path
        }
    }

    private class SpeechCallbacks : NexusSpeechCallbacks {
        val started = mutableListOf<Boolean>()
        val states = mutableListOf<NexusSpeechState>()
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val stopped = mutableListOf<Pair<NexusSpeechStopReason, NexusSpeechError?>>()

        override fun onSpeechStarted(realtime: Boolean) {
            started += realtime
        }

        override fun onSpeechState(state: NexusSpeechState) {
            states += state
        }

        override fun onSpeechPartial(text: String) {
            partials += text
        }

        override fun onSpeechFinal(text: String) {
            finals += text
        }

        override fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
            stopped += reason to error
        }
    }

    private data class Fixture(
        val client: NexusPluginClient,
        val transport: FakeTransport,
        val pluginCallbacks: PluginCallbacks,
    )

    private fun fixture(capabilities: String = "stt"): Fixture {
        val transport = FakeTransport()
        val pluginCallbacks = PluginCallbacks()
        val client = NexusPluginClient("hello", pluginCallbacks, transport)
        client.connect()
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-1",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities),
        )
        return Fixture(client, transport, pluginCallbacks)
    }

    private fun accept(
        transport: FakeTransport,
        id: String = "start-reply",
        sessionId: String = "session-1",
        realtime: Boolean = true,
        pluginId: String = "hello",
    ) {
        transport.listener.onMessage(
            NEXUS_STT_SESSION_START_REPLY_PATH,
            id,
            JSONObject()
                .put("pluginId", pluginId)
                .put("accepted", true)
                .put("sessionId", sessionId)
                .put("realtime", realtime),
        )
    }

    @Test
    fun `speech state machine routes matching events and structured completion`() {
        val fixture = fixture()
        val callbacks = SpeechCallbacks()
        val session = fixture.client.speechSession(callbacks)

        assertEquals(NexusSdkResult.SENT, session.start("fr"))
        assertFalse(session.isActive)
        val start = fixture.transport.sends.single()
        assertEquals(NEXUS_STT_SESSION_START_PATH, start.path)
        assertEquals(1, start.payload.getInt("version"))
        assertEquals("utterance", start.payload.getString("mode"))
        assertEquals("fr", start.payload.getString("language"))

        accept(fixture.transport)
        assertTrue(session.isActive)
        assertEquals(listOf(true), callbacks.started)

        fixture.transport.listener.onMessage(
            NEXUS_STT_STATE_PATH,
            "session-1:s0",
            JSONObject()
                .put("pluginId", "hello")
                .put("sessionId", "session-1")
                .put("state", "recognizing"),
        )
        fixture.transport.listener.onMessage(
            NEXUS_STT_PARTIAL_PATH,
            "session-1:p0",
            JSONObject()
                .put("pluginId", "hello")
                .put("sessionId", "session-1")
                .put("text", "bon"),
        )
        fixture.transport.listener.onMessage(
            NEXUS_STT_FINAL_PATH,
            "session-1:final",
            JSONObject()
                .put("pluginId", "hello")
                .put("sessionId", "session-1")
                .put("text", "bonjour"),
        )
        assertTrue(session.isActive)
        fixture.transport.listener.onMessage(
            NEXUS_STT_SESSION_ENDED_PATH,
            "session-1:ended",
            JSONObject()
                .put("pluginId", "hello")
                .put("sessionId", "session-1")
                .put("reason", "completed"),
        )

        assertEquals(listOf(NexusSpeechState.RECOGNIZING), callbacks.states)
        assertEquals(listOf("bon"), callbacks.partials)
        assertEquals(listOf("bonjour"), callbacks.finals)
        assertEquals(listOf(NexusSpeechStopReason.COMPLETED to null), callbacks.stopped)
        assertFalse(session.isActive)
        assertTrue(fixture.pluginCallbacks.genericMessages.isEmpty())
    }

    @Test
    fun `denials map to stable SDK reasons and unknown denial is invalid`() {
        val fixture = fixture()
        val callbacks = SpeechCallbacks()
        val session = fixture.client.speechSession(callbacks)
        val cases = listOf(
            "BUSY" to NexusSpeechStopReason.DENIED_BUSY,
            "NO_LINK" to NexusSpeechStopReason.DENIED_NO_LINK,
            "NOT_READY" to NexusSpeechStopReason.DENIED_NOT_READY,
            "START_FAILED" to NexusSpeechStopReason.DENIED_START_FAILED,
            "INVALID_REQUEST" to NexusSpeechStopReason.DENIED_INVALID,
            "FUTURE_REASON" to NexusSpeechStopReason.DENIED_INVALID,
        )

        cases.forEachIndexed { index, (wireReason, _) ->
            assertEquals(NexusSdkResult.SENT, session.start())
            fixture.transport.listener.onMessage(
                NEXUS_STT_SESSION_START_REPLY_PATH,
                "denied-$index",
                JSONObject()
                    .put("pluginId", "hello")
                    .put("accepted", false)
                    .put("reason", wireReason),
            )
        }

        assertEquals(cases.map { it.second }, callbacks.stopped.map { it.first })
    }

    @Test
    fun `plugin mismatch and duplicate event ids are dropped`() {
        val fixture = fixture()
        val callbacks = SpeechCallbacks()
        val session = fixture.client.speechSession(callbacks)
        assertEquals(NexusSdkResult.SENT, session.start())

        accept(fixture.transport, id = "same-reply", pluginId = "other")
        assertFalse(session.isActive)
        accept(fixture.transport, id = "same-reply")
        assertTrue(session.isActive)

        val partial = JSONObject()
            .put("pluginId", "hello")
            .put("sessionId", "session-1")
            .put("text", "first")
        fixture.transport.listener.onMessage(NEXUS_STT_PARTIAL_PATH, "duplicate", partial)
        fixture.transport.listener.onMessage(
            NEXUS_STT_PARTIAL_PATH,
            "duplicate",
            JSONObject(partial.toString()).put("text", "second"),
        )
        fixture.transport.listener.onMessage(
            NEXUS_STT_PARTIAL_PATH,
            "wrong-session",
            JSONObject(partial.toString()).put("sessionId", "session-2"),
        )

        assertEquals(listOf("first"), callbacks.partials)
    }

    @Test
    fun `stop is idempotent and speech routing stays sticky after completion`() {
        val fixture = fixture()
        val callbacks = SpeechCallbacks()
        val session = fixture.client.speechSession(callbacks)
        assertEquals(NexusSdkResult.SENT, session.start())
        accept(fixture.transport)

        session.stop()
        session.stop()

        assertEquals(2, fixture.transport.sends.size)
        val stop = fixture.transport.sends.last()
        assertEquals(NEXUS_STT_SESSION_STOP_PATH, stop.path)
        assertEquals("session-1", stop.payload.getString("sessionId"))
        assertEquals(listOf(NexusSpeechStopReason.CANCELLED), callbacks.stopped.map { it.first })
        assertFalse(session.isActive)

        assertEquals(NexusSdkResult.SENT, session.start())
        accept(
            fixture.transport,
            id = "start-reply-2",
            sessionId = "session-2",
            realtime = false,
        )
        fixture.transport.listener.onMessage(
            NEXUS_STT_SESSION_STOP_REPLY_PATH,
            "late-stop-reply",
            JSONObject().put("pluginId", "hello").put("stopped", true),
        )
        assertTrue(session.isActive)

        fixture.transport.listener.onMessage(
            "/stt/future",
            "future-event",
            JSONObject().put("pluginId", "hello"),
        )
        fixture.transport.listener.onBinary(
            "/stt/future-binary",
            "future-binary",
            JSONObject().put("pluginId", "hello"),
            byteArrayOf(1),
        )
        assertTrue(fixture.pluginCallbacks.genericMessages.isEmpty())
    }

    @Test
    fun `service close stops active speech while client close terminates with error`() {
        val serviceFixture = fixture()
        val serviceCallbacks = SpeechCallbacks()
        val serviceSession = serviceFixture.client.speechSession(serviceCallbacks)
        serviceFixture.transport.listener.onMessage(
            BusPaths.PLUGIN_OPEN,
            "open-1",
            JSONObject().put("pluginId", "hello"),
        )
        assertEquals(NexusSdkResult.SENT, serviceSession.start())
        accept(serviceFixture.transport)
        serviceFixture.transport.listener.onMessage(
            BusPaths.PLUGIN_CLOSE,
            "close-1",
            JSONObject().put("pluginId", "hello"),
        )
        assertEquals(
            listOf(NexusSpeechStopReason.CANCELLED),
            serviceCallbacks.stopped.map { it.first },
        )
        assertEquals(NEXUS_STT_SESSION_STOP_PATH, serviceFixture.transport.sends.last().path)

        val closeFixture = fixture()
        val closeCallbacks = SpeechCallbacks()
        val closeSession = closeFixture.client.speechSession(closeCallbacks)
        assertEquals(NexusSdkResult.SENT, closeSession.start())
        accept(closeFixture.transport)
        closeFixture.client.close()
        assertEquals(listOf(NexusSpeechStopReason.ERROR), closeCallbacks.stopped.map { it.first })
        assertTrue(closeFixture.transport.closed)
    }

    @Test
    fun `ended error is exposed and capability is checked before sending`() {
        val fixture = fixture()
        val callbacks = SpeechCallbacks()
        val session = fixture.client.speechSession(callbacks)
        assertEquals(NexusSdkResult.SENT, session.start())
        accept(fixture.transport)
        fixture.transport.listener.onMessage(
            NEXUS_STT_SESSION_ENDED_PATH,
            "session-1:ended",
            JSONObject()
                .put("pluginId", "hello")
                .put("sessionId", "session-1")
                .put("reason", "error")
                .put(
                    "error",
                    JSONObject()
                        .put("kind", "NETWORK")
                        .put("provider", "OpenAI")
                        .put("detail", "Provider network request failed"),
                ),
        )
        assertEquals(
            NexusSpeechError("NETWORK", "OpenAI", "Provider network request failed"),
            callbacks.stopped.single().second,
        )

        val withoutGrant = fixture("surfaces")
        val denied = withoutGrant.client.speechSession(SpeechCallbacks())
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, denied.start())
        assertTrue(withoutGrant.transport.sends.isEmpty())
    }
}
