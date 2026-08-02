package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsContractTest {
    @Test
    fun `speak normalizes line breaks and preserves the opaque id`() {
        val result = TtsContract.validateSpeak(
            JSONObject()
                .put("utteranceId", " opaque id ")
                .put("text", "  first\r\nsecond\n\nthird  "),
        )

        assertTrue(result is TtsValidationResult.Valid)
        assertEquals(
            TtsSpeakRequest(" opaque id ", "first second third"),
            (result as TtsValidationResult.Valid).value,
        )
    }

    @Test
    fun `speak preserves an optional BCP 47 language tag`() {
        val absent = TtsContract.validateSpeak(TtsContract.speakPayload("u1", "bonjour"))
        val present = TtsContract.validateSpeak(TtsContract.speakPayload("u2", "bonjour", "fr-FR"))

        assertEquals(null, (absent as TtsValidationResult.Valid).value.lang)
        assertEquals("fr-FR", (present as TtsValidationResult.Valid).value.lang)
        assertEquals("fr-FR", TtsContract.speakPayload("u2", "bonjour", "fr-FR").getString("lang"))
    }

    @Test
    fun `speak rejects blank oversized and wrongly typed fields`() {
        listOf(
            JSONObject().put("utteranceId", "id").put("text", "\n\r"),
            JSONObject().put("utteranceId", "x".repeat(TtsContract.MAX_UTTERANCE_ID_CHARS + 1)).put("text", "hi"),
            JSONObject().put("utteranceId", "id").put("text", "x".repeat(TtsContract.MAX_TEXT_CHARS + 1)),
            JSONObject().put("utteranceId", 7).put("text", "hi"),
            JSONObject().put("utteranceId", "id").put("text", true),
            JSONObject().put("utteranceId", "id").put("text", "hi").put("lang", 7),
            JSONObject().put("utteranceId", "id").put("text", "hi").put("lang", "not_a_tag"),
        ).forEach { payload ->
            assertEquals(
                TtsValidationResult.Invalid(TtsContract.ERROR_INVALID_TTS),
                TtsContract.validateSpeak(payload),
            )
        }
    }

    @Test
    fun `routed requests require a valid injected owner`() {
        val routed = TtsContract.withOwner(
            TtsContract.speakPayload("id", " hello "),
            "hello.plugin",
        )
        val valid = TtsContract.validateSpeak(routed, requireOwner = true)
        assertTrue(valid is TtsValidationResult.Valid)
        assertEquals("hello.plugin", (valid as TtsValidationResult.Valid).value.ownerPluginId)

        routed.put("ownerPluginId", "Bad owner")
        assertEquals(
            TtsValidationResult.Invalid(TtsContract.ERROR_INVALID_TTS),
            TtsContract.validateSpeak(routed, requireOwner = true),
        )
    }

    @Test
    fun `started and done accept only the protocol reasons`() {
        val started = TtsContract.startedPayload("hello.plugin", "id")
        assertTrue(TtsContract.validateStarted(started) is TtsValidationResult.Valid)
        assertEquals(TtsDoneReason.CANCELLED, TtsDoneReason.fromWireValue("CANCELLED"))
        TtsDoneReason.entries.forEach { reason ->
            assertTrue(
                TtsContract.validateDone(
                    TtsContract.donePayload("hello.plugin", "id", reason),
                ) is TtsValidationResult.Valid,
            )
        }
        assertEquals(
            TtsValidationResult.Invalid(TtsContract.ERROR_INVALID_TTS),
            TtsContract.validateDone(JSONObject(started.toString()).put("reason", "completed")),
        )
    }
}
