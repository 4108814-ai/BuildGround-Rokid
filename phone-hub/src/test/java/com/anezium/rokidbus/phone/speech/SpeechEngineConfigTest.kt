package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineConfigTest {
    @Test
    fun registryContainsOnlyTheSevenCloudEngines() {
        assertEquals(
            listOf(
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
        assertFalse(SpeechEngine.values().any { it.id.contains("android") })
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
