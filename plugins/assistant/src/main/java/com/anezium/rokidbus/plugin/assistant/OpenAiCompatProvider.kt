package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface OpenAiCompatChatClient {
    fun streamChat(
        request: ChatRequest,
        modelId: String,
        requestId: String = request.requestId,
    ): Flow<String>

    fun cancel(requestId: String)
}

class OpenAiCompatApiClient(
    private val preset: ProviderPreset,
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String = { preset.defaultBaseUrl },
    private val effortProvider: () -> String = { "" },
) : OpenAiCompatChatClient {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    override fun streamChat(
        request: ChatRequest,
        modelId: String,
        requestId: String,
    ): Flow<String> = flow {
        val connection = openConnection().apply {
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            outputStream.use { output ->
                output.write(requestBody(request, modelId).toString().toByteArray(Charsets.UTF_8))
            }
        }
        activeConnections.put(requestId, connection)?.disconnect()
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(
                    "${preset.displayName} chat failed ($status): ${connection.safeErrorBody()}",
                )
            }
            readSse(connection) { event ->
                when (event) {
                    is OpenAiChatSseEvent.TextDelta -> {
                        emit(event.text)
                        true
                    }
                    is OpenAiChatSseEvent.Error -> throw IllegalStateException(event.message)
                    OpenAiChatSseEvent.Done -> false
                    OpenAiChatSseEvent.Ignored -> true
                }
            }
        } finally {
            activeConnections.remove(requestId, connection)
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

    internal fun requestBody(request: ChatRequest, modelId: String): JSONObject =
        JSONObject()
            .put("model", modelId)
            .put("stream", true)
            .put("messages", request.toChatCompletionMessages())
            .apply {
                val effort = effortProvider().trim()
                if (effort in preset.supportedEfforts) {
                    put("reasoning", JSONObject().put("effort", effort))
                }
            }

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

class OpenAiCompatProvider internal constructor(
    private val preset: ProviderPreset,
    private val apiClient: OpenAiCompatChatClient,
    private val apiKeyConfigured: () -> Boolean,
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

        val response = StringBuilder()
        val thinkTagFilter = ThinkTagStreamFilter()
        try {
            apiClient.streamChat(
                request = request.forVisionSupport(supportsVision()),
                modelId = request.model ?: modelProvider(),
                requestId = request.requestId,
            ).collect { delta ->
                if (delta.isEmpty()) return@collect
                val filteredDelta = thinkTagFilter.filter(delta)
                if (filteredDelta.isEmpty()) return@collect
                response.append(filteredDelta)
                emit(AiProviderEvent.TextDelta(messageId, filteredDelta))
            }
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
        val remaining = thinkTagFilter.finish()
        if (remaining.isNotEmpty()) {
            response.append(remaining)
            emit(AiProviderEvent.TextDelta(messageId, remaining))
        }
        val cleanResponse = response.toString()
            .replace(INLINE_THINK_BLOCKS, "")
            .trim()
        emit(
            AiProviderEvent.MessageDone(
                ChatMessage(
                    id = messageId,
                    role = "assistant",
                    content = cleanResponse,
                ),
            ),
        )
    }

    override suspend fun cancel(requestId: String) {
        apiClient.cancel(requestId)
    }
}

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
