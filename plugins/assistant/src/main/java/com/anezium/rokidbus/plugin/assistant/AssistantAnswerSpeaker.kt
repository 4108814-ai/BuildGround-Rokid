package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusSdkResult

/** Dispatches only complete assistant answers, with one stable id per completed turn. */
internal class AssistantAnswerSpeaker(
    private val enabled: () -> Boolean,
    private val speak: (text: String, utteranceId: String) -> NexusSdkResult,
) {
    private var utteranceCounter = 0L

    fun speakCompletedAnswer(text: String): NexusSdkResult? {
        if (!enabled() || text.isBlank()) return null
        utteranceCounter += 1
        return speak(text, "answer-$utteranceCounter")
    }
}
