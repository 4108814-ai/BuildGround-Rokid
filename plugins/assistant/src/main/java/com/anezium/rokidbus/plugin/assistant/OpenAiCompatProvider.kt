package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class OpenAiCompatChatRequest(
    val request: ChatRequest,
    val modelId: String,
    val messages: JSONArray,
    val includeTakePhotoTool: Boolean,
    val requestId: String = request.requestId,
)

internal interface OpenAiCompatChatClient {
    fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent>

    fun cancel(requestId: String)
}

internal class OpenAiCompatApiClient(
    private val preset: ProviderPreset,
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String = { preset.defaultBaseUrl },
    private val effortProvider: () -> String = { "" },
) : OpenAiCompatChatClient {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
        val connection = openConnection().apply {
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            outputStream.use { output ->
                output.write(requestBody(request).toString().toByteArray(Charsets.UTF_8))
            }
        }
        activeConnections.put(request.requestId, connection)?.disconnect()
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenAiCompatHttpException(
                    statusCode = status,
                    message =
                        "${preset.displayName} chat failed ($status): ${connection.safeErrorBody()}",
                )
            }
            readSse(connection) { event ->
                when (event) {
                    is OpenAiChatSseEvent.Delta -> {
                        emit(event)
                        true
                    }
                    is OpenAiChatSseEvent.Error -> throw IllegalStateException(event.message)
                    OpenAiChatSseEvent.Done -> false
                    OpenAiChatSseEvent.Ignored -> true
                }
            }
        } finally {
            activeConnections.remove(request.requestId, connection)
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    internal fun endpointUrl(): String {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        check(baseUrl.isNotEmpty()) { "${preset.displayName} base URL is not configured." }
        return "$baseUrl/chat/completions"
    }

    internal fun requestBody(request: OpenAiCompatChatRequest): JSONObject =
        JSONObject()
            .put("model", request.modelId)
            .put("stream", true)
            .put("messages", request.messages)
            .apply {
                val effort = effortProvider().trim()
                if (effort in preset.supportedEfforts) {
                    put("reasoning", JSONObject().put("effort", effort))
                }
                if (request.includeTakePhotoTool) {
                    put("tools", JSONArray().put(takePhotoToolDeclaration()))
                }
            }

    internal fun requestBody(
        request: ChatRequest,
        modelId: String,
        includeTakePhotoTool: Boolean = false,
    ): JSONObject = requestBody(
        OpenAiCompatChatRequest(
            request = request,
            modelId = modelId,
            messages = request.toChatCompletionMessages(),
            includeTakePhotoTool = includeTakePhotoTool,
        ),
    )

    private fun takePhotoToolDeclaration(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", TAKE_PHOTO_TOOL_NAME)
                    .put(
                        "description",
                        "Take one photo through the glasses camera to see what is currently in " +
                            "front of the wearer.",
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject())
                            .put("additionalProperties", false),
                    ),
            )

    private fun openConnection(): HttpURLConnection =
        (URL(endpointUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKeyProvider().orEmpty()}")
            preset.extraHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

    private suspend fun readSse(
        connection: HttpURLConnection,
        consume: suspend (OpenAiChatSseEvent) -> Boolean,
    ) {
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            val data = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                when {
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            val keepReading = consume(OpenAiChatSseParser.parseData(data.toString()))
                            data.clear()
                            if (!keepReading) return
                        }
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
            }
            if (data.isNotEmpty()) consume(OpenAiChatSseParser.parseData(data.toString()))
        }
    }
}

internal class OpenAiCompatHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal class OpenAiToolCallAccumulator {
    private data class PartialCall(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private val partialCalls = sortedMapOf<Int, PartialCall>()

    fun append(deltas: List<OpenAiChatToolCallDelta>) {
        deltas.forEach { delta ->
            val partial = partialCalls.getOrPut(delta.index, ::PartialCall)
            if (partial.id.isNullOrBlank() && !delta.id.isNullOrBlank()) {
                partial.id = delta.id
            }
            delta.nameFragment?.let { fragment -> partial.name.append(fragment) }
            delta.argumentsFragment?.let { fragment -> partial.arguments.append(fragment) }
        }
    }

    fun completeCalls(): List<AssistantToolCall> = partialCalls.map { (index, partial) ->
        AssistantToolCall(
            callId = partial.id?.takeIf(String::isNotBlank) ?: "call_$index",
            name = partial.name.toString(),
            argumentsJson = partial.arguments.toString(),
        )
    }
}

private data class OpenAiCompatPassResult(
    val text: String,
    val toolCalls: List<AssistantToolCall>,
    val toolsDeclared: Boolean,
)

internal class OpenAiCompatProvider(
    private val preset: ProviderPreset,
    private val apiClient: OpenAiCompatChatClient,
    private val apiKeyConfigured: () -> Boolean,
    private val toolExecutor: AssistantToolExecutor,
    private val modelProvider: () -> String = { preset.defaultModel },
    private val supportsVision: () -> Boolean,
) : AiProvider {
    override val id: String = preset.id
    override val displayName: String = preset.displayName

    override fun streamEvents(request: ChatRequest): Flow<AiProviderEvent> = flow {
        val messageId = UUID.randomUUID().toString()
        emit(AiProviderEvent.Started(messageId))
        if (!apiKeyConfigured()) {
            emit(AiProviderEvent.Failed("${preset.displayName} API key is not configured."))
            return@flow
        }

        try {
            val visionSupported = supportsVision()
            val effectiveRequest = request.forVisionSupport(visionSupported)
            val modelId = request.model ?: modelProvider()
            val originalMessages = effectiveRequest.toChatCompletionMessages()

            suspend fun streamPass(
                messages: JSONArray,
                includeTakePhotoTool: Boolean,
            ): OpenAiCompatPassResult {
                val response = StringBuilder()
                val toolCalls = OpenAiToolCallAccumulator()
                val thinkTagFilter = ThinkTagStreamFilter()
                apiClient.streamChat(
                    OpenAiCompatChatRequest(
                        request = effectiveRequest,
                        modelId = modelId,
                        messages = messages,
                        includeTakePhotoTool = includeTakePhotoTool,
                        requestId = request.requestId,
                    ),
                ).collect { event ->
                    when (event) {
                        is OpenAiChatSseEvent.Delta -> {
                            toolCalls.append(event.toolCalls)
                            val content = event.content.orEmpty()
                            if (content.isEmpty()) return@collect
                            val filteredDelta = thinkTagFilter.filter(content)
                            if (filteredDelta.isEmpty()) return@collect
                            response.append(filteredDelta)
                            emit(AiProviderEvent.TextDelta(messageId, filteredDelta))
                        }
                        is OpenAiChatSseEvent.Error ->
                            throw IllegalStateException(event.message)
                        OpenAiChatSseEvent.Done,
                        OpenAiChatSseEvent.Ignored,
                        -> Unit
                    }
                }
                val remaining = thinkTagFilter.finish()
                if (remaining.isNotEmpty()) {
                    response.append(remaining)
                    emit(AiProviderEvent.TextDelta(messageId, remaining))
                }
                return OpenAiCompatPassResult(
                    text = cleanCompatResponse(response.toString()),
                    toolCalls = toolCalls.completeCalls(),
                    toolsDeclared = includeTakePhotoTool,
                )
            }

            val firstPass = try {
                streamPass(originalMessages, includeTakePhotoTool = visionSupported)
            } catch (error: OpenAiCompatHttpException) {
                if (!visionSupported || error.statusCode !in 400..499) throw error
                streamPass(originalMessages, includeTakePhotoTool = false)
            }
            currentCoroutineContext().ensureActive()

            val finalText = if (firstPass.toolsDeclared && firstPass.toolCalls.isNotEmpty()) {
                emit(AiProviderEvent.TextReset(messageId))
                val replayMessages = originalMessages.copyJsonArray()
                replayMessages.put(assistantToolCallMessage(firstPass.text, firstPass.toolCalls))
                var executorClaimed = false
                var capturedPhoto: PhotoAttachment? = null
                firstPass.toolCalls.forEach { call ->
                    val result = when {
                        !isValidTakePhotoCall(call) ->
                            AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL)
                        executorClaimed ->
                            AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED)
                        else -> {
                            executorClaimed = true
                            executeToolSafely(call)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (result is AssistantToolResult.Image) {
                        capturedPhoto = PhotoAttachment(result.mimeType, result.base64)
                    }
                    replayMessages.put(toolResultMessage(call, result))
                }
                capturedPhoto?.let { photo ->
                    replayMessages.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                chatCompletionContent(
                                    PHOTO_TAKEN_MESSAGE,
                                    listOf(photo),
                                ),
                            ),
                    )
                }
                streamPass(replayMessages, includeTakePhotoTool = false).text
            } else {
                firstPass.text
            }

            emit(
                AiProviderEvent.MessageDone(
                    ChatMessage(
                        id = messageId,
                        role = "assistant",
                        content = finalText,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.conciseProviderMessage("Request failed.")
            val message = if (detail.contains(preset.displayName, ignoreCase = true)) {
                detail
            } else {
                "${preset.displayName} request failed: $detail".take(240)
            }
            emit(
                AiProviderEvent.Failed(message),
            )
            return@flow
        }
    }

    override suspend fun cancel(requestId: String) {
        apiClient.cancel(requestId)
    }

    private suspend fun executeToolSafely(call: AssistantToolCall): AssistantToolResult =
        try {
            currentCoroutineContext().ensureActive()
            toolExecutor.execute(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED)
        }
}

private fun assistantToolCallMessage(
    text: String,
    calls: List<AssistantToolCall>,
): JSONObject =
    JSONObject()
        .put("role", "assistant")
        .put("content", text)
        .put(
            "tool_calls",
            JSONArray().apply {
                calls.forEach { call ->
                    put(
                        JSONObject()
                            .put("id", call.callId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.argumentsJson),
                            ),
                    )
                }
            },
        )

private fun toolResultMessage(
    call: AssistantToolCall,
    result: AssistantToolResult,
): JSONObject {
    val content = when (result) {
        is AssistantToolResult.Image ->
            JSONObject()
                .put("status", "captured")
                .put("note", "The photo is attached to the next user message.")
        is AssistantToolResult.Error ->
            JSONObject()
                .put("status", "error")
                .put("code", result.code)
    }
    return JSONObject()
        .put("role", "tool")
        .put("tool_call_id", call.callId)
        .put("content", content.toString())
}

private fun JSONArray.copyJsonArray(): JSONArray = JSONArray().also { copy ->
    for (index in 0 until length()) copy.put(get(index))
}

private fun cleanCompatResponse(response: String): String =
    response.replace(INLINE_THINK_BLOCKS, "").trim()

internal fun ChatRequest.forVisionSupport(supportsVision: Boolean): ChatRequest {
    if (supportsVision) return this
    val hadCurrentPhoto = photos.any { photo -> !photo.isOmittedHistoryPhoto() }
    val textOnlyUserText = if (hadCurrentPhoto) {
        listOf(userText, PHOTO_UNSUPPORTED_NOTE)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    } else {
        userText
    }
    return copy(
        userText = textOnlyUserText,
        history = history.map { message -> message.copy(photos = emptyList()) },
        photos = emptyList(),
    )
}

internal const val PHOTO_UNSUPPORTED_NOTE =
    "[A photo was attached, but the selected model cannot view images.]"

private val INLINE_THINK_BLOCKS =
    Regex("<think>.*?</think>|<think>.*$", RegexOption.DOT_MATCHES_ALL)

private const val PHOTO_TAKEN_MESSAGE =
    "Photo just taken through the glasses camera for the current question."
