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

internal sealed interface OpenAiChatSseEvent {
    data class TextDelta(val text: String) : OpenAiChatSseEvent
    data class Error(val message: String) : OpenAiChatSseEvent
    data object Done : OpenAiChatSseEvent
    data object Ignored : OpenAiChatSseEvent
}

internal object OpenAiChatSseParser {
    fun parseData(payload: String): OpenAiChatSseEvent {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return OpenAiChatSseEvent.Ignored
        if (trimmed == "[DONE]") return OpenAiChatSseEvent.Done

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return OpenAiChatSseEvent.Ignored
        json.optJSONObject("error")?.let { error ->
            return OpenAiChatSseEvent.Error(
                error.optString("message").ifBlank { "OpenAI stream error." },
            )
        }
        val choices = json.optJSONArray("choices") ?: return OpenAiChatSseEvent.Ignored
        val delta = choices.optJSONObject(0)?.optJSONObject("delta")
            ?: return OpenAiChatSseEvent.Ignored
        if (!delta.has("content") || delta.isNull("content")) return OpenAiChatSseEvent.Ignored
        val text = delta.optString("content")
        return if (text.isEmpty()) OpenAiChatSseEvent.Ignored else OpenAiChatSseEvent.TextDelta(text)
    }

    fun parseLine(line: String): OpenAiChatSseEvent {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return OpenAiChatSseEvent.Ignored
        return parseData(trimmed.removePrefix("data:").trim())
    }
}

class OpenAiApiClient(
    private val apiKeyProvider: () -> String?,
    private val endpointProvider: () -> String = { DEFAULT_ENDPOINT },
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
                .put("model", supportedModel(modelId))
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
                throw IllegalStateException("OpenAI chat failed ($status): ${connection.safeErrorBody()}")
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
        val endpoint = endpointProvider().trim().ifBlank { DEFAULT_ENDPOINT }
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKeyProvider().orEmpty()}")
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
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL_ID = "gpt-4o-mini"
        val SUPPORTED_MODELS = setOf("gpt-4o", DEFAULT_MODEL_ID)

        fun supportedModel(modelId: String): String =
            modelId.trim().takeIf(SUPPORTED_MODELS::contains) ?: DEFAULT_MODEL_ID
    }
}

class OpenAiProvider(
    private val apiClient: OpenAiApiClient,
    private val apiKeyConfigured: () -> Boolean,
    private val modelProvider: () -> String = { OpenAiApiClient.DEFAULT_MODEL_ID },
) : AiProvider {
    override val id: String = ID
    override val displayName: String = "OpenAI"

    override fun streamEvents(request: ChatRequest): Flow<AiProviderEvent> = flow {
        val messageId = UUID.randomUUID().toString()
        emit(AiProviderEvent.Started(messageId))
        if (!apiKeyConfigured()) {
            emit(AiProviderEvent.Failed("Connect your ChatGPT account in settings."))
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
            emit(AiProviderEvent.Failed(error.conciseProviderMessage("OpenAI request failed.")))
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
        const val ID = "openai"
    }
}

internal fun HttpURLConnection.safeErrorBody(): String = runCatching {
    (errorStream ?: inputStream)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
        .redactProviderSecrets()
        .take(480)
}.getOrDefault("no error body")

internal fun Throwable.conciseProviderMessage(fallback: String): String =
    message
        ?.redactProviderSecrets()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(240)
        ?.takeIf(String::isNotBlank)
        ?: fallback

internal fun String.redactProviderSecrets(): String {
    var output = replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+"""), "Bearer [redacted]")
    output = output.replace(Regex("""\b(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{20,})\b"""), "[api-key]")
    output = output.replace(
        Regex("""(?i)(access_token|refresh_token|id_token|token)=([^&\s]+)"""),
        "$1=[redacted]",
    )
    return output
}
