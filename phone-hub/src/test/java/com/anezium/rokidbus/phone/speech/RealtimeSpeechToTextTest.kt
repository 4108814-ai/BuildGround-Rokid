package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSpeechToTextTest {
    @Test
    fun forcedCantoneseSessionUpdateHasPromptAndNoLanguageField() {
        val language = openAiLanguageFor(
            forcedLanguage = TranscriptionLanguage.CANTONESE,
            phoneLanguageTag = "en-US",
        )
        assertNull(language)

        val update = openAiSessionUpdate(
            model = "gpt-realtime-whisper",
            language = language,
            prompt = TranscriptionLanguage.CANTONESE.openAiPrompt,
        )
        val transcription = update
            .getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")

        assertEquals("gpt-realtime-whisper", transcription.getString("model"))
        assertEquals("廣東話語音。請用繁體中文轉寫。", transcription.getString("prompt"))
        assertFalse(transcription.has("language"))
    }

    @Test
    fun autoLanguageMayUseThePhoneLocale() {
        assertEquals(
            "fr",
            openAiLanguageFor(TranscriptionLanguage.AUTO, "fr-FR"),
        )
    }

    @Test
    fun resamplerProducesThreeOutputSamplesForTwoInputSamples() {
        val input = byteArrayOf(0, 0, 0x10, 0)
        val output = Pcm16Resampler.upsample16kTo24k(input)
        assertEquals(6, output.size)
        assertTrue(output.any { it.toInt() != 0 })
    }
}
