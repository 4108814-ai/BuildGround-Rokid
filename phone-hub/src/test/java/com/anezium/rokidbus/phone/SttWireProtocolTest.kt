package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.SttErrorKind
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttWireProtocolTest {
    @Test
    fun `start parser accepts utterance v1 and falls back for unknown language`() {
        assertEquals(
            TranscriptionLanguage.FRENCH,
            SttWireProtocol.parseStart(
                JSONObject()
                    .put("version", 1)
                    .put("mode", "utterance")
                    .put("language", "fr"),
            )?.language,
        )
        assertNull(
            SttWireProtocol.parseStart(
                JSONObject()
                    .put("version", 1)
                    .put("mode", "utterance")
                    .put("language", "not-a-language"),
            )?.language,
        )
        assertNull(
            SttWireProtocol.parseStart(
                JSONObject().put("version", 2).put("mode", "utterance"),
            ),
        )
        assertNull(
            SttWireProtocol.parseStart(
                JSONObject().put("version", 1).put("mode", "continuous"),
            ),
        )
    }

    @Test
    fun `start denial reasons use the stable wire values`() {
        assertEquals("BUSY", SttWireProtocol.startDenialReason(SpeechStartResult.BUSY))
        assertEquals("NO_LINK", SttWireProtocol.startDenialReason(SpeechStartResult.NO_LINK))
        assertEquals("NOT_READY", SttWireProtocol.startDenialReason(SpeechStartResult.NOT_READY))
        assertEquals(
            "START_FAILED",
            SttWireProtocol.startDenialReason(SpeechStartResult.START_FAILED),
        )
    }

    @Test
    fun `event payloads stamp holder and use unique protocol ids`() {
        val state = SttWireProtocol.statePayload(
            pluginId = "scribe",
            sessionId = "session-1",
            state = SpeechSessionState.RECOGNIZING,
        )
        assertEquals(1, state.getInt("version"))
        assertEquals("scribe", state.getString("pluginId"))
        assertEquals("session-1", state.getString("sessionId"))
        assertEquals("recognizing", state.getString("state"))
        assertEquals("session-1:s3", SttWireProtocol.stateId("session-1", 3))

        val partial = SttWireProtocol.partialPayload("scribe", "session-1", "bonjour", 7)
        assertEquals("bonjour", partial.getString("text"))
        assertEquals(7L, partial.getLong("seq"))
        assertEquals("session-1:p7", SttWireProtocol.partialId("session-1", 7))
        assertEquals("session-1:final", SttWireProtocol.finalId("session-1"))
        assertEquals("session-1:ended", SttWireProtocol.endedId("session-1"))
    }

    @Test
    fun `ended payload maps reasons and structured errors exactly`() {
        val ended = SttWireProtocol.endedPayload(
            pluginId = "scribe",
            sessionId = "session-1",
            reason = SpeechEndReason.ERROR,
            error = SttError(
                kind = SttErrorKind.NETWORK,
                providerLabel = "OpenAI",
                detail = "Provider network request failed",
            ),
        )
        assertEquals("error", ended.getString("reason"))
        val error = ended.getJSONObject("error")
        assertEquals("NETWORK", error.getString("kind"))
        assertEquals("OpenAI", error.getString("provider"))
        assertEquals("Provider network request failed", error.getString("detail"))

        val noSpeech = SttWireProtocol.endedPayload(
            "scribe",
            "session-2",
            SpeechEndReason.NO_SPEECH,
            null,
        )
        assertEquals("no_speech", noSpeech.getString("reason"))
        assertFalse(noSpeech.has("error"))

        val revoked = SttWireProtocol.revokedPayload("scribe", "session-3")
        assertEquals("revoked", revoked.getString("reason"))
        assertTrue(revoked.has("pluginId"))
    }
}
