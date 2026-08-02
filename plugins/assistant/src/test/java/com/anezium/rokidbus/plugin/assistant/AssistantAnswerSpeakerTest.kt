package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantAnswerSpeakerTest {
    @Test
    fun speakingIsSkippedWhenSettingIsOff() {
        val calls = mutableListOf<SpeakCall>()
        val speaker = speaker(enabled = false, calls = calls)

        assertNull(speaker.speakCompletedAnswer("Finished answer"))
        assertEquals(emptyList<SpeakCall>(), calls)
    }

    @Test
    fun completedAnswerIsSpokenOnceAfterStreamingNotPerChunk() {
        val calls = mutableListOf<SpeakCall>()
        val speaker = speaker(enabled = true, calls = calls)
        val answer = buildString {
            listOf("One ", "complete ", "answer.").forEach { chunk ->
                append(chunk)
                assertEquals(emptyList<SpeakCall>(), calls)
            }
        }

        assertEquals(NexusSdkResult.SENT, speaker.speakCompletedAnswer(answer))
        assertEquals(listOf(SpeakCall("One complete answer.", "answer-1")), calls)
    }

    @Test
    fun emptyCompletedAnswerIsNotSpoken() {
        val calls = mutableListOf<SpeakCall>()
        val speaker = speaker(enabled = true, calls = calls)

        assertNull(speaker.speakCompletedAnswer(""))
        assertEquals(emptyList<SpeakCall>(), calls)
    }

    @Test
    fun capabilityNotAvailableIsReturnedWithoutThrowingOrSurfacingAnError() {
        val calls = mutableListOf<SpeakCall>()
        val speaker = speaker(
            enabled = true,
            calls = calls,
            result = NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
        )

        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            speaker.speakCompletedAnswer("Finished answer"),
        )
        assertEquals(listOf(SpeakCall("Finished answer", "answer-1")), calls)
    }

    private fun speaker(
        enabled: Boolean,
        calls: MutableList<SpeakCall>,
        result: NexusSdkResult = NexusSdkResult.SENT,
    ): AssistantAnswerSpeaker = AssistantAnswerSpeaker(
        enabled = { enabled },
        speak = { text, utteranceId ->
            calls += SpeakCall(text, utteranceId)
            result
        },
    )

    private data class SpeakCall(val text: String, val utteranceId: String)
}
