package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OpenAiProviderSseTest {
    @Test
    fun parsesChatCompletionTextDelta() {
        val event = OpenAiChatSseParser.parseLine(
            """data: {"choices":[{"delta":{"content":"Bonjour"}}]}""",
        )

        assertEquals(OpenAiChatSseEvent.TextDelta("Bonjour"), event)
    }

    @Test
    fun recognizesDoneAndIgnoresRoleOnlyChunks() {
        assertSame(
            OpenAiChatSseEvent.Done,
            OpenAiChatSseParser.parseLine("data: [DONE]"),
        )
        assertSame(
            OpenAiChatSseEvent.Ignored,
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            ),
        )
    }

    @Test
    fun parsesProviderErrorWithoutThrowingAwayMessage() {
        val event = OpenAiChatSseParser.parseLine(
            """data: {"error":{"message":"rate limited"}}""",
        )

        assertEquals(OpenAiChatSseEvent.Error("rate limited"), event)
    }

    @Test
    fun openAiAndOpenRouterProvidersDoNotEmitTextReset() = runTest {
        val request = ChatRequest(userText = "Hello")
        val openAiEvents = OpenAiProvider(
            apiClient = OpenAiApiClient(apiKeyProvider = { null }),
            apiKeyConfigured = { false },
        ).streamEvents(request).toList()
        val openRouterEvents = OpenRouterProvider(
            apiClient = OpenRouterApiClient(apiKeyProvider = { null }),
            apiKeyConfigured = { false },
        ).streamEvents(request).toList()

        assertFalse(openAiEvents.any { it is AiProviderEvent.TextReset })
        assertFalse(openRouterEvents.any { it is AiProviderEvent.TextReset })
    }
}
