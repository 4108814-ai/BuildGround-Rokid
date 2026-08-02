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

class OpenRouterApiClient(
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },
) {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    fun streamChat(
        request: ChatRequest,
        modelId: String,
        requestId: String = request.requestId,
    ): Flow<String> = flow {
        val connection = openConnection().apply {
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            val body = JSONObject()
                .put("model", modelId.trim().ifBlank { DEFAULT_MODEL_ID })
                .put("stream", true)
                .put("messages", request.toChatCompletionMessages())
            outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }
        }
        activeConnections.put(requestId, connection)?.disconnect()
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("OpenRouter chat failed ($status): ${connection.safeErrorBody()}")
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

    fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    private fun openConnection(): HttpURLConnection {
        val baseUrl = baseUrlProvider().trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKeyProvider().orEmpty()}")
            setRequestProperty("HTTP-Referer", "https://github.com/Anezium/Rokid-Nexus")
            setRequestProperty("X-Title", "Rokid Nexus Assistant")
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

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL_ID = "openrouter/auto"
    }
}

class OpenRouterProvider(
    private val apiClient: OpenRouterApiClient,
    private val apiKeyConfigured: () -> Boolean,
    private val modelProvider: () -> String = { OpenRouterApiClient.DEFAULT_MODEL_ID },
) : AiProvider {
    override val id: String = ID
    override val displayName: String = "OpenRouter"

    override fun streamEvents(request: ChatRequest): Flow<AiProviderEvent> = flow {
        val messageId = UUID.randomUUID().toString()
        emit(AiProviderEvent.Started(messageId))
        if (!apiKeyConfigured()) {
            emit(AiProviderEvent.Failed("OpenRouter API key is not configured."))
            return@flow
        }

        val response = StringBuilder()
        try {
            apiClient.streamChat(
                request = request,
                modelId = request.model ?: modelProvider(),
                requestId = request.requestId,
            ).collect { delta ->
                if (delta.isEmpty()) return@collect
                response.append(delta)
                emit(AiProviderEvent.TextDelta(messageId, delta))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(AiProviderEvent.Failed(error.conciseProviderMessage("OpenRouter request failed.")))
            return@flow
        }
        emit(
            AiProviderEvent.MessageDone(
                ChatMessage(
                    id = messageId,
                    role = "assistant",
                    content = response.toString(),
                ),
            ),
        )
    }

    override suspend fun cancel(requestId: String) {
        apiClient.cancel(requestId)
    }

    companion object {
        const val ID = "openrouter"
    }
}
