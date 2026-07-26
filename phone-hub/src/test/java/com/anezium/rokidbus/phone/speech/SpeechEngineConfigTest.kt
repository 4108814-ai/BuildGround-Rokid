package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineConfigTest {
    @Test
    fun registryContainsAndroidAndTheSevenCloudEngines() {
        assertEquals(
            listOf(
                "android_recognizer",
                "openai_gpt_realtime_whisper",
                "openai_gpt_4o_transcribe",
                "openai_gpt_4o_mini_transcribe",
                "elevenlabs_scribe_v2_realtime",
                "elevenlabs_scribe_v2",
                "elevenlabs_scribe_v1",
                "azure_speech",
            ),
            SpeechEngine.values().map { it.id },
        )
    }

    @Test
    fun registryPreservesModelsAndEngineShapes() {
        assertEquals(
            "gpt-realtime-whisper",
            SpeechEngine.OPENAI_GPT_REALTIME_WHISPER.realtimeModelId,
        )
        assertTrue(SpeechEngine.OPENAI_GPT_REALTIME_WHISPER.usesRealtime)
        assertFalse(SpeechEngine.OPENAI_GPT_REALTIME_WHISPER.usesCompletedAudio)
        assertTrue(SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE.usesCompletedAudio)
        assertTrue(SpeechEngine.ELEVENLABS_SCRIBE_V2_REALTIME.usesRealtime)
        assertTrue(SpeechEngine.AZURE_SPEECH.usesCompletedAudio)
        assertTrue(SpeechEngine.ANDROID_RECOGNIZER.usesAndroidRecognizer)
        assertFalse(SpeechEngine.ANDROID_RECOGNIZER.usesRealtime)
        assertFalse(SpeechEngine.ANDROID_RECOGNIZER.usesCompletedAudio)
        assertNull(SpeechEngine.ANDROID_RECOGNIZER.completedAudioModelId)
        assertNull(SpeechEngine.ANDROID_RECOGNIZER.realtimeModelId)
        assertEquals("Android Built-in recognizer", SpeechEngine.ANDROID_RECOGNIZER.displayName)
        // The engine cards strip the provider prefix, so the remainder must still name the engine.
        assertEquals(
            "Built-in recognizer",
            SpeechEngine.ANDROID_RECOGNIZER.displayName
                .removePrefix(SpeechProvider.ANDROID.displayName)
                .trim(),
        )
        assertEquals("Android", SpeechEngine.ANDROID_RECOGNIZER.shortLabel)
        assertEquals(
            "Works straight away — no account, no API key, nothing to pay.",
            SpeechEngine.ANDROID_RECOGNIZER.choiceDescription,
        )
        assertEquals(
            listOf("Live text", "No key", "Phone engine"),
            SpeechEngine.ANDROID_RECOGNIZER.choiceBadges,
        )
        assertSame(SpeechCredentialKind.NONE, SpeechEngine.ANDROID_RECOGNIZER.credentialKind)
    }

    @Test
    fun everyEngineShipsRealCopy() {
        SpeechEngine.values().forEach { engine ->
            val copy = listOf(engine.displayName, engine.shortLabel, engine.choiceDescription) +
                engine.choiceBadges
            copy.forEach { text ->
                assertTrue("${engine.id} has blank copy", text.isNotBlank())
                assertFalse("${engine.id} still ships placeholder copy", text.contains("TODO"))
            }
            assertTrue("${engine.id} has no badges", engine.choiceBadges.isNotEmpty())
        }
    }

    @Test
    fun unknownAndUnsetIdsDoNotChooseAnEngine() {
        assertNull(SpeechEngine.fromId(null))
        assertNull(SpeechEngine.fromId(" "))
        assertNull(SpeechEngine.fromId("android_cxr"))
        assertNull(SpeechEngine.fromId("not-an-engine"))
    }

    @Test
    fun knownIdsAreTrimmedAndCaseInsensitive() {
        SpeechEngine.values().forEach { engine ->
            assertSame(engine, SpeechEngine.fromId("  ${engine.id.uppercase()}  "))
        }
    }
}
