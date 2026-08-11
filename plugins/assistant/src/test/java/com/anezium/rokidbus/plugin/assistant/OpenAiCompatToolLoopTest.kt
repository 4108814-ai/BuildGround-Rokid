package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatToolLoopTest {
    @Test
    fun `photo tool call replays output and attached image without redeclaring tools`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = "<think>hidden first pass</think>Looking",
                    ),
                    takePhotoCallDelta(callId = "call-photo"),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = "<think>hidden second pass</think>The label says 42.",
                    ),
                ),
            ),
        )
        val executed = mutableListOf<AssistantToolCall>()
        val provider = provider(
            client = client,
            toolExecutor = { call ->
                executed += call
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Read this")).toList()

        assertEquals(1, executed.size)
        assertEquals(TAKE_PHOTO_TOOL_NAME, executed.single().name)
        assertTrue(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            listOf("Looking", "The label says 42."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(
            events.filterIsInstance<AiProviderEvent.TextDelta>()
                .any { event -> event.delta.contains("hidden") || event.delta.contains("think") },
        )
        assertEquals(
            "The label says 42.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )

        assertEquals(2, client.requests.size)
        val firstBody = bodyFor(client.requests[0])
        val tools = firstBody.getJSONArray("tools")
        assertEquals(1, tools.length())
        val declaration = tools.getJSONObject(0)
        assertEquals("function", declaration.getString("type"))
        val function = declaration.getJSONObject("function")
        assertEquals(TAKE_PHOTO_TOOL_NAME, function.getString("name"))
        assertEquals(
            TAKE_PHOTO_TOOL_DESCRIPTION,
            function.getString("description"),
        )
        val parameters = function.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertFalse(firstBody.has("tool_choice"))

        val secondBody = bodyFor(client.requests[1])
        assertFalse(secondBody.has("tools"))
        assertFalse(secondBody.has("tool_choice"))
        val messages = secondBody.getJSONArray("messages")
        assertEquals(4, messages.length())

        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("Looking", assistant.getString("content"))
        val echoedCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-photo", echoedCall.getString("id"))
        assertEquals("function", echoedCall.getString("type"))
        assertEquals(TAKE_PHOTO_TOOL_NAME, echoedCall.getJSONObject("function").getString("name"))
        assertEquals("{}", echoedCall.getJSONObject("function").getString("arguments"))

        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-photo", tool.getString("tool_call_id"))
        assertToolContent(
            tool.getString("content"),
            "status" to "captured",
            "note" to "The photo is attached to the next user message.",
        )

        val photoMessage = messages.getJSONObject(3)
        assertEquals("user", photoMessage.getString("role"))
        val photoParts = photoMessage.getJSONArray("content")
        assertEquals(2, photoParts.length())
        assertEquals("text", photoParts.getJSONObject(0).getString("type"))
        assertEquals(
            "Photo just taken through the glasses camera for the current question. " +
                "The image is attached now; do not request another photo.",
            photoParts.getJSONObject(0).getString("text"),
        )
        assertEquals("image_url", photoParts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AQID",
            photoParts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `text fallback token captures and attaches a photo without leaking onto the hud`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "<think>Need vision</think>  [[NEXUS_"),
                    OpenAiChatSseEvent.Delta(content = "TAKE_PHOTO]]\n"),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "I can see a green door.")),
            ),
        )
        var executionCount = 0
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "What do you see?")).toList()

        assertEquals(1, executionCount)
        assertTrue(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            listOf("I can see a green door."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(events.any { event -> event.toString().contains("NEXUS_TAKE_PHOTO") })
        assertEquals(2, client.requests.size)
        val replayMessages = client.requests[1].messages
        assertEquals(2, replayMessages.length())
        assertEquals("user", replayMessages.getJSONObject(1).getString("role"))
        val photoParts = replayMessages.getJSONObject(1).getJSONArray("content")
        assertEquals(
            "data:image/jpeg;base64,AQID",
            photoParts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `generic OpenAI compatible provider does not interpret the Hermes control token`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = COMPAT_TAKE_PHOTO_REQUEST_TOKEN),
                ),
            ),
        )
        var executionCount = 0
        val provider = provider(
            client = client,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Say the token")).toList()

        assertEquals(0, executionCount)
        assertEquals(1, client.requests.size)
        assertEquals(
            listOf(COMPAT_TAKE_PHOTO_REQUEST_TOKEN),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map(AiProviderEvent.TextDelta::delta),
        )
    }

    @Test
    fun `only explicit or detected Hermes carries a safe conversation session header`() {
        val conversationId = "7baf8322-8919-4b20-95dc-5337ad5b6769"
        val request = OpenAiCompatChatRequest(
            request = ChatRequest(userText = "Hello", conversationId = conversationId),
            modelId = "hermes-agent",
            messages = ChatRequest(userText = "Hello").toChatCompletionMessages(),
            toolDefinitions = emptyList(),
        )
        val hermesClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.hermes,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://hermes.test/v1" },
        )
        val genericCustomClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://custom.test/v1" },
        )
        val detectedCustomClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://hermes.test/v1" },
            backendProvider = { ProviderBackend.HERMES },
        )
        val openAiClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.openAi,
            apiKeyProvider = { "key" },
        )

        assertEquals(conversationId, hermesClient.hermesSessionIdHeader(request))
        assertEquals(null, genericCustomClient.hermesSessionIdHeader(request))
        assertEquals(conversationId, detectedCustomClient.hermesSessionIdHeader(request))
        assertEquals(null, openAiClient.hermesSessionIdHeader(request))
        assertEquals(
            null,
            hermesClient.hermesSessionIdHeader(
                request.copy(request = request.request.copy(conversationId = "bad\nsession")),
            ),
        )
    }

    @Test
    fun `tool error replays compact error without photo message`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(takePhotoCallDelta(callId = "call-error")),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Please try again.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = {
                AssistantToolResult.Error(TOOL_ERROR_CAMERA_BUSY)
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Look")).toList()

        val messages = client.requests[1].messages
        assertEquals(3, messages.length())
        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertToolContent(
            tool.getString("content"),
            "status" to "error",
            "code" to "camera_busy",
        )
        assertFalse(
            (0 until messages.length()).any { index ->
                val message = messages.getJSONObject(index)
                message.optString("role") == "user" && message.opt("content") !is String
            },
        )
        assertEquals(
            "Please try again.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `test only second tool is declared dispatched and replayed as json`() = runTest {
        val lookupTool = TestAssistantTool(
            name = "lookup_note",
            description = "Look up a saved note.",
            executor = { _, _ ->
                AssistantToolResult.Json("""{"ok":true,"title":"Groceries"}""")
            },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        toolCalls = listOf(
                            OpenAiChatToolCallDelta(
                                index = 0,
                                id = "call-note",
                                nameFragment = "lookup_note",
                                argumentsFragment = "{}",
                            ),
                        ),
                        finishReason = "tool_calls",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Milk and bread.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(lookupTool),
        )

        provider.streamEvents(ChatRequest(userText = "Read my groceries note")).toList()

        val tools = bodyFor(client.requests[0]).getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals(
            TAKE_PHOTO_TOOL_NAME,
            tools.getJSONObject(0).getJSONObject("function").getString("name"),
        )
        assertEquals(
            "lookup_note",
            tools.getJSONObject(1).getJSONObject("function").getString("name"),
        )
        val replayMessages = client.requests[1].messages
        assertEquals(3, replayMessages.length())
        assertEquals("tool", replayMessages.getJSONObject(2).getString("role"))
        assertEquals(
            """{"ok":true,"title":"Groceries"}""",
            replayMessages.getJSONObject(2).getString("content"),
        )
    }

    @Test
    fun `only first of two valid tool calls reaches executor`() = runTest {
        var executionCount = 0
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        toolCalls = listOf(
                            completeToolDelta(index = 0, callId = "call-first"),
                            completeToolDelta(index = 1, callId = "call-second"),
                        ),
                        finishReason = "tool_calls",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Done.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/png", "cGhvdG8=")
            },
        )

        provider.streamEvents(ChatRequest(userText = "Look twice")).toList()

        assertEquals(1, executionCount)
        val messages = client.requests[1].messages
        assertToolContent(
            messages.getJSONObject(2).getString("content"),
            "status" to "captured",
            "note" to "The photo is attached to the next user message.",
        )
        assertToolContent(
            messages.getJSONObject(3).getString("content"),
            "status" to "error",
            "code" to "already_used",
        )
        assertEquals("user", messages.getJSONObject(4).getString("role"))
    }

    @Test
    fun `non vision model sends no tool declaration`() = runTest {
        val client = RecordingCompatClient(
            listOf(StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Text only."))),
        )
        val provider = provider(
            client = client,
            supportsVision = false,
            toolExecutor = { error("Tool must not execute") },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().toolDefinitions.isEmpty())
        assertFalse(bodyFor(client.requests.single()).has("tools"))
        assertFalse(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            "Text only.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `non vision model still declares an available non vision tool`() = runTest {
        val client = RecordingCompatClient(
            listOf(StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Text only."))),
        )
        val lookupTool = TestAssistantTool(
            name = "lookup_note",
            description = "Look up a saved note.",
        )
        val provider = provider(
            client = client,
            supportsVision = false,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(lookupTool),
        )

        provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        val tools = bodyFor(client.requests.single()).getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals(
            "lookup_note",
            tools.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `four hundred response with tools retries once without tools`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Failure(OpenAiCompatHttpException(422, "tools unsupported")),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Fallback answer.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = { error("Tool must not execute") },
        )
        val request = ChatRequest(userText = "Hello")

        val events = provider.streamEvents(request).toList()

        assertEquals(2, client.requests.size)
        assertEquals(
            listOf(true, false),
            client.requests.map { it.toolDefinitions.isNotEmpty() },
        )
        assertTrue(client.requests.all { it.requestId == request.requestId })
        assertEquals(
            listOf("Fallback answer."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
        assertEquals(
            "Fallback answer.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    private fun provider(
        client: RecordingCompatClient,
        toolExecutor: suspend (AssistantToolCall) -> AssistantToolResult,
        supportsVision: Boolean = true,
        additionalTools: List<AssistantToolDefinition> = emptyList(),
        preset: ProviderPreset = ProviderCatalog.openAi,
    ) = OpenAiCompatProvider(
        preset = preset,
        apiClient = client,
        apiKeyConfigured = { true },
        toolRegistry = testToolRegistry(toolExecutor, *additionalTools.toTypedArray()),
        supportsVision = { supportsVision },
        backendProvider = { preset.backend },
    )

    private fun assertToolContent(content: String, vararg fields: Pair<String, String>) {
        val json = JSONObject(content)
        assertEquals(fields.size, json.length())
        fields.forEach { (key, value) -> assertEquals(value, json.getString(key)) }
    }

    private fun bodyFor(request: OpenAiCompatChatRequest): JSONObject =
        OpenAiCompatApiClient(
            preset = ProviderCatalog.openAi,
            apiKeyProvider = { "key" },
        ).requestBody(request)

    private fun takePhotoCallDelta(callId: String): OpenAiChatSseEvent.Delta =
        OpenAiChatSseEvent.Delta(
            toolCalls = listOf(completeToolDelta(index = 0, callId = callId)),
            finishReason = "tool_calls",
        )

    private fun completeToolDelta(index: Int, callId: String) =
        OpenAiChatToolCallDelta(
            index = index,
            id = callId,
            nameFragment = TAKE_PHOTO_TOOL_NAME,
            argumentsFragment = "{}",
        )

    private sealed interface StubResponse {
        data class Events(val events: List<OpenAiChatSseEvent>) : StubResponse {
            constructor(vararg events: OpenAiChatSseEvent) : this(events.toList())
        }

        data class Failure(val error: Throwable) : StubResponse
    }

    private class RecordingCompatClient(
        responses: List<StubResponse>,
    ) : OpenAiCompatChatClient {
        private val remainingResponses = ArrayDeque(responses)
        val requests = mutableListOf<OpenAiCompatChatRequest>()

        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> {
            requests += request
            val response = remainingResponses.removeFirst()
            return flow {
                when (response) {
                    is StubResponse.Events -> response.events.forEach { event -> emit(event) }
                    is StubResponse.Failure -> throw response.error
                }
            }
        }

        override fun cancel(requestId: String) = Unit
    }
}
