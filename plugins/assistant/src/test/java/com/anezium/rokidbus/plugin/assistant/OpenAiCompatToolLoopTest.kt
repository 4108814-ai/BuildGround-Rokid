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
            toolExecutor = AssistantToolExecutor { call ->
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
            "Take one photo through the glasses camera to see what is currently in front of the wearer.",
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
            "Photo just taken through the glasses camera for the current question.",
            photoParts.getJSONObject(0).getString("text"),
        )
        assertEquals("image_url", photoParts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AQID",
            photoParts.getJSONObject(1).getJSONObject("image_url").getString("url"),
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
            toolExecutor = AssistantToolExecutor {
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
            toolExecutor = AssistantToolExecutor {
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
            toolExecutor = AssistantToolExecutor { error("Tool must not execute") },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, client.requests.size)
        assertFalse(client.requests.single().includeTakePhotoTool)
        assertFalse(bodyFor(client.requests.single()).has("tools"))
        assertFalse(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            "Text only.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
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
            toolExecutor = AssistantToolExecutor { error("Tool must not execute") },
        )
        val request = ChatRequest(userText = "Hello")

        val events = provider.streamEvents(request).toList()

        assertEquals(2, client.requests.size)
        assertEquals(listOf(true, false), client.requests.map { it.includeTakePhotoTool })
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
        toolExecutor: AssistantToolExecutor,
        supportsVision: Boolean = true,
    ) = OpenAiCompatProvider(
        preset = ProviderCatalog.openAi,
        apiClient = client,
        apiKeyConfigured = { true },
        toolExecutor = toolExecutor,
        supportsVision = { supportsVision },
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
