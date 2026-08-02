package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.TtsContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTtsRequestGateTest {
    @Test
    fun `accepted speak is normalized and stamped with verified owner`() {
        val result = PhoneTtsRequestGate { 0L }.evaluate(
            ownerPluginId = "hello",
            path = BusPaths.TTS_SPEAK,
            payload = JSONObject()
                .put("utteranceId", "u1")
                .put("text", "  hello\nthere  ")
                .put("lang", "fr-FR")
                .put("ownerPluginId", "spoofed"),
            hasBinary = false,
        )

        assertTrue(result is PhoneTtsGateResult.Accepted)
        val payload = (result as PhoneTtsGateResult.Accepted).payload
        assertEquals("u1", payload.getString("utteranceId"))
        assertEquals("hello there", payload.getString("text"))
        assertEquals("fr-FR", payload.getString("lang"))
        assertEquals("hello", payload.getString("ownerPluginId"))
    }

    @Test
    fun `invalid shapes and binary commands fail closed`() {
        val gate = PhoneTtsRequestGate { 0L }
        listOf(
            gate.evaluate("hello", BusPaths.TTS_SPEAK, JSONObject().put("utteranceId", "u1"), false),
            gate.evaluate("hello", BusPaths.TTS_STOP, JSONObject().put("utteranceId", ""), false),
            gate.evaluate(
                "hello",
                BusPaths.TTS_SPEAK,
                JSONObject().put("utteranceId", "u1").put("text", "hello"),
                true,
            ),
        ).forEach { result ->
            assertEquals(
                PhoneTtsGateResult.Rejected(TtsContract.ERROR_INVALID_TTS),
                result,
            )
        }
    }

    @Test
    fun `speak and stop share one five command sliding window`() {
        var now = 0L
        val gate = PhoneTtsRequestGate { now }
        repeat(TtsContract.MAX_MESSAGES_PER_SECOND) { index ->
            val path = if (index % 2 == 0) BusPaths.TTS_SPEAK else BusPaths.TTS_STOP
            val payload = JSONObject().put("utteranceId", "u$index")
            if (path == BusPaths.TTS_SPEAK) payload.put("text", "hello")
            assertTrue(gate.evaluate("hello", path, payload, false) is PhoneTtsGateResult.Accepted)
        }
        assertEquals(
            PhoneTtsGateResult.Rejected(TtsContract.ERROR_TTS_RATE_LIMITED),
            gate.evaluate(
                "hello",
                BusPaths.TTS_SPEAK,
                JSONObject().put("utteranceId", "last").put("text", "hello"),
                false,
            ),
        )
        now = 1_000L
        assertTrue(
            gate.evaluate(
                "hello",
                BusPaths.TTS_STOP,
                JSONObject().put("utteranceId", "last"),
                false,
            ) is PhoneTtsGateResult.Accepted,
        )
    }

    @Test
    fun `capability requires matching protocol and a live control transport`() {
        assertFalse(PhoneTtsCapabilityPolicy.isAvailable(TtsContract.VERSION, 0))
        assertFalse(PhoneTtsCapabilityPolicy.isAvailable(0, LinkStateBits.CXR_CONTROL_UP))
        assertTrue(
            PhoneTtsCapabilityPolicy.isAvailable(
                TtsContract.VERSION,
                LinkStateBits.CXR_CONTROL_UP,
            ),
        )
        assertTrue(
            PhoneTtsCapabilityPolicy.isAvailable(
                TtsContract.VERSION,
                LinkStateBits.SPP_DATA_UP,
            ),
        )
    }
}
