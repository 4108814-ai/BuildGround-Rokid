package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun ignoresReasoningOnlyChunks() {
        assertSame(
            OpenAiChatSseEvent.Ignored,
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"reasoning_content":"Thinking"}}]}""",
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
    fun compatibleProviderDoesNotEmitTextReset() = runTest {
        val events = OpenAiCompatProvider(
            preset = ProviderCatalog.openRouter,
            apiClient = RecordingCompatClient(),
            apiKeyConfigured = { false },
            supportsVision = { false },
        ).streamEvents(ChatRequest(userText = "Hello")).toList()

        assertFalse(events.any { it is AiProviderEvent.TextReset })
    }

    @Test
    fun nonVisionProviderDropsAllPhotosAndNotesCurrentAttachment() = runTest {
        val client = RecordingCompatClient()
        val provider = OpenAiCompatProvider(
            preset = ProviderCatalog.deepSeek,
            apiClient = client,
            apiKeyConfigured = { true },
            supportsVision = { false },
        )

        provider.streamEvents(
            ChatRequest(
                userText = "What is this?",
                history = listOf(
                    ChatMessage(
                        role = "user",
                        content = "What was there?",
                        photos = listOf(PhotoAttachment("image/jpeg", "history-bytes")),
                    ),
                ),
                photos = listOf(PhotoAttachment("image/jpeg", "current-bytes")),
            ),
        ).toList()

        val captured = client.request
        val serialized = captured.toChatCompletionMessages().toString()
        assertTrue(captured.userText.endsWith(PHOTO_UNSUPPORTED_NOTE))
        assertTrue(captured.photos.isEmpty())
        assertTrue(captured.history.all { it.photos.isEmpty() })
        assertFalse(serialized.contains("image_url"))
        assertFalse(serialized.contains("history-bytes"))
        assertFalse(serialized.contains("current-bytes"))
    }

    @Test
    fun openRouterEffortIsIncludedOnlyWhenConfigured() {
        val withEffort = OpenAiCompatApiClient(
            preset = ProviderCatalog.openRouter,
            apiKeyProvider = { "key" },
            effortProvider = { "high" },
        ).requestBody(ChatRequest(userText = "Hello"), "free-text/model")
        val withoutEffort = OpenAiCompatApiClient(
            preset = ProviderCatalog.openRouter,
            apiKeyProvider = { "key" },
            effortProvider = { "" },
        ).requestBody(ChatRequest(userText = "Hello"), "free-text/model")

        assertEquals("free-text/model", withEffort.getString("model"))
        assertEquals("high", withEffort.getJSONObject("reasoning").getString("effort"))
        assertFalse(withoutEffort.has("reasoning"))
    }

    @Test
    fun baseUrlOverrideIsUsedForChatCompletionsEndpoint() {
        val client = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { " https://example.test/openai/v1/ " },
        )

        assertEquals(
            "https://example.test/openai/v1/chat/completions",
            client.endpointUrl(),
        )
    }

    private class RecordingCompatClient : OpenAiCompatChatClient {
        lateinit var request: ChatRequest

        override fun streamChat(
            request: ChatRequest,
            modelId: String,
            requestId: String,
        ) = flowOf("done").also {
            this.request = request
        }

        override fun cancel(requestId: String) = Unit
    }
}
