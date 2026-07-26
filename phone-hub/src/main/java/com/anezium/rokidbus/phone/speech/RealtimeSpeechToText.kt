package com.anezium.rokidbus.phone.speech

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Locale

internal object ApiRealtimeSpeechToText {
    fun create(
        client: OkHttpClient,
        secrets: HubSecretStore,
        engine: SpeechEngine,
        listener: SttSessionListener,
        forcedLanguage: TranscriptionLanguage,
        phoneLanguageTag: String,
    ): SttSession {
        require(engine.usesRealtime) { "${engine.id} is not a realtime STT engine" }
        val model = requireNotNull(engine.realtimeModelId)
        return when (engine.provider) {
            SpeechProvider.ANDROID -> error("${engine.id} is not a realtime STT engine")
            SpeechProvider.OPENAI -> OpenAiRealtimeSpeechToTextSession(
                client = client,
                apiKey = secrets.apiKey(SpeechCredentialKind.OPENAI)?.trim().orEmpty(),
                model = model,
                listener = listener,
                forcedLanguage = forcedLanguage,
                phoneLanguageTag = phoneLanguageTag,
            )
            SpeechProvider.ELEVENLABS -> ElevenLabsRealtimeSpeechToTextSession(
                client = client,
                apiKey = secrets.apiKey(SpeechCredentialKind.ELEVENLABS)?.trim().orEmpty(),
                model = model,
                listener = listener,
                forcedLanguage = forcedLanguage,
                phoneLanguageTag = phoneLanguageTag,
            )
            SpeechProvider.AZURE -> error("${engine.id} is not a realtime STT engine")
        }
    }
}

private class OpenAiRealtimeSpeechToTextSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val listener: SttSessionListener,
    private val forcedLanguage: TranscriptionLanguage,
    private val phoneLanguageTag: String,
) : WebSocketListener(), SttSession {
    private val chunker = PcmChunker(INPUT_CHUNK_BYTES) { chunk ->
        sendChunk(Pcm16Resampler.upsample16kTo24k(chunk))
    }
    private val partial = StringBuilder()
    private val outboundLock = Any()
    private val pendingChunks = ArrayDeque<ByteArray>()
    private var pendingCommit = false

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var sessionUpdateSent = false
    @Volatile private var terminalDelivered = false

    override fun start(): Boolean {
        if (closed || apiKey.isBlank()) {
            if (!closed) {
                fail(
                    SttError(
                        SttErrorKind.AUTH,
                        SpeechProvider.OPENAI.displayName,
                        "Provider credential is missing",
                    ),
                )
            }
            return false
        }
        val request = Request.Builder()
            .url("$OPENAI_REALTIME_URL?intent=transcription")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, this)
        return true
    }

    override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        if (!closed) chunker.append(data, offset, length)
    }

    override fun finishAudio() {
        if (closed) return
        chunker.flush()
        val commitNow = synchronized(outboundLock) {
            if (ready) true else {
                pendingCommit = true
                false
            }
        }
        if (commitNow) sendCommit()
    }

    override fun cancel() {
        closed = true
        chunker.clear()
        synchronized(outboundLock) {
            pendingChunks.clear()
            pendingCommit = false
        }
        socket?.close(1000, "cancel")
        socket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (closed) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "session.created" -> sendSessionUpdate(webSocket)
            "session.updated" -> emitReady()
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = obj.optString("delta")
                if (delta.isNotEmpty()) {
                    partial.append(delta)
                    listener.onPartial(partial.toString())
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = obj.optString("transcript").trim().ifBlank {
                    partial.toString().trim()
                }
                if (transcript.isBlank()) {
                    fail(
                        SttError(
                            SttErrorKind.NO_SPEECH,
                            SpeechProvider.OPENAI.displayName,
                            "Provider did not recognize speech",
                        ),
                    )
                } else {
                    terminalDelivered = true
                    listener.onFinal(transcript)
                }
            }
            "conversation.item.input_audio_transcription.failed",
            "error",
            -> fail(
                providerError(
                    SpeechProvider.OPENAI,
                    providerMessage = obj.errorMessage(),
                ),
            )
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!closed) {
            fail(
                providerError(
                    SpeechProvider.OPENAI,
                    statusCode = response?.code,
                    failure = t,
                ),
            )
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed && !terminalDelivered) {
            fail(
                SttError(
                    SttErrorKind.NETWORK,
                    SpeechProvider.OPENAI.displayName,
                    "Realtime connection closed before a final result",
                ),
            )
        }
    }

    private fun sendSessionUpdate(webSocket: WebSocket) {
        if (sessionUpdateSent || closed) return
        sessionUpdateSent = true
        val language = if (forcedLanguage == TranscriptionLanguage.AUTO) {
            languageCode(phoneLanguageTag)
        } else {
            forcedLanguage.openAiCode
        }
        webSocket.send(
            openAiSessionUpdate(
                model = model,
                language = language,
                prompt = forcedLanguage.openAiPrompt,
            ).toString(),
        )
    }

    private fun emitReady() {
        if (ready || closed) return
        val (chunks, commit) = synchronized(outboundLock) {
            if (ready || closed) return
            ready = true
            val queued = pendingChunks.toList()
            pendingChunks.clear()
            val shouldCommit = pendingCommit
            pendingCommit = false
            queued to shouldCommit
        }
        chunks.forEach(::sendChunkNow)
        if (commit) sendCommit()
        listener.onReady()
    }

    private fun sendChunk(bytes: ByteArray) {
        val sendNow = synchronized(outboundLock) {
            if (ready) true else {
                pendingChunks.addLast(bytes)
                false
            }
        }
        if (sendNow) sendChunkNow(bytes)
    }

    private fun sendChunkNow(bytes: ByteArray) {
        if (closed || terminalDelivered) return
        val sent = socket?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString(),
        ) == true
        if (!sent) {
            fail(
                SttError(
                    SttErrorKind.NETWORK,
                    SpeechProvider.OPENAI.displayName,
                    "Realtime audio delivery failed",
                ),
            )
        }
    }

    private fun sendCommit() {
        if (closed || terminalDelivered) return
        val sent = socket?.send(
            JSONObject().put("type", "input_audio_buffer.commit").toString(),
        ) == true
        if (!sent) {
            fail(
                SttError(
                    SttErrorKind.NETWORK,
                    SpeechProvider.OPENAI.displayName,
                    "Realtime connection is unavailable",
                ),
            )
        }
    }

    private fun fail(error: SttError) {
        if (closed || terminalDelivered) return
        terminalDelivered = true
        listener.onError(error)
    }

    private companion object {
        const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        const val INPUT_CHUNK_BYTES = 3_200
    }
}

private class ElevenLabsRealtimeSpeechToTextSession(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val listener: SttSessionListener,
    private val forcedLanguage: TranscriptionLanguage,
    private val phoneLanguageTag: String,
) : WebSocketListener(), SttSession {
    private val chunker = PcmChunker(CHUNK_BYTES) { chunk -> sendChunk(chunk, commit = false) }
    private val outboundLock = Any()
    private val pendingChunks = ArrayDeque<PendingElevenLabsChunk>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var terminalDelivered = false

    override fun start(): Boolean {
        if (closed || apiKey.isBlank()) {
            if (!closed) {
                fail(
                    SttError(
                        SttErrorKind.AUTH,
                        SpeechProvider.ELEVENLABS.displayName,
                        "Provider credential is missing",
                    ),
                )
            }
            return false
        }
        val language = if (forcedLanguage == TranscriptionLanguage.AUTO) {
            languageCode(phoneLanguageTag)
        } else {
            forcedLanguage.elevenLabsCode
        }
        val request = Request.Builder()
            .url(elevenLabsRealtimeUrl(model, language))
            .addHeader("xi-api-key", apiKey)
            .build()
        socket = client.newWebSocket(request, this)
        return true
    }

    override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        if (!closed) chunker.append(data, offset, length)
    }

    override fun finishAudio() {
        if (closed) return
        val remainder = chunker.drain()
        sendChunk(remainder ?: ByteArray(0), commit = true)
    }

    override fun cancel() {
        closed = true
        chunker.clear()
        synchronized(outboundLock) {
            pendingChunks.clear()
        }
        socket?.close(1000, "cancel")
        socket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (closed) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("message_type")) {
            "session_started" -> emitReady()
            "partial_transcript" -> listener.onPartial(obj.optString("text"))
            "committed_transcript",
            "committed_transcript_with_timestamps",
            -> {
                val transcript = obj.optString("text").trim()
                if (transcript.isBlank()) {
                    fail(
                        SttError(
                            SttErrorKind.NO_SPEECH,
                            SpeechProvider.ELEVENLABS.displayName,
                            "Provider did not recognize speech",
                        ),
                    )
                } else {
                    terminalDelivered = true
                    listener.onFinal(transcript)
                }
            }
            "auth_error",
            "quota_exceeded",
            "throttled",
            "rate_limited",
            "unaccepted_terms",
            "unaccepted_terms_error",
            "queue_overflow",
            "queue_overflow_error",
            "resource_exhausted",
            "resource_exhausted_error",
            "session_time_limit_exceeded_error",
            "input_error",
            "chunk_size_exceeded",
            "insufficient_audio_activity",
            "transcriber_error",
            "commit_throttled",
            "error",
            -> fail(
                providerError(
                    SpeechProvider.ELEVENLABS,
                    providerMessage = obj.errorMessage().ifBlank { obj.optString("message_type") },
                ),
            )
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!closed) {
            fail(
                providerError(
                    SpeechProvider.ELEVENLABS,
                    statusCode = response?.code,
                    failure = t,
                ),
            )
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (!closed && !terminalDelivered) {
            fail(
                SttError(
                    SttErrorKind.NETWORK,
                    SpeechProvider.ELEVENLABS.displayName,
                    "Realtime connection closed before a final result",
                ),
            )
        }
    }

    private fun emitReady() {
        if (ready || closed) return
        val chunks = synchronized(outboundLock) {
            if (ready || closed) return
            ready = true
            pendingChunks.toList().also { pendingChunks.clear() }
        }
        chunks.forEach { sendChunkNow(it.bytes, it.commit) }
        listener.onReady()
    }

    private fun sendChunk(bytes: ByteArray, commit: Boolean) {
        val sendNow = synchronized(outboundLock) {
            if (ready) true else {
                pendingChunks.addLast(PendingElevenLabsChunk(bytes, commit))
                false
            }
        }
        if (sendNow) sendChunkNow(bytes, commit)
    }

    private fun sendChunkNow(bytes: ByteArray, commit: Boolean) {
        if (closed || terminalDelivered) return
        val payload = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("sample_rate", SAMPLE_RATE)
            .put("commit", commit)
        val sent = socket?.send(payload.toString()) == true
        if (!sent) {
            fail(
                SttError(
                    SttErrorKind.NETWORK,
                    SpeechProvider.ELEVENLABS.displayName,
                    "Realtime audio delivery failed",
                ),
            )
        }
    }

    private fun fail(error: SttError) {
        if (closed || terminalDelivered) return
        terminalDelivered = true
        listener.onError(error)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 3_200
    }

    private data class PendingElevenLabsChunk(
        val bytes: ByteArray,
        val commit: Boolean,
    )
}

internal class PcmChunker(
    private val chunkBytes: Int,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val buffer = ByteArrayOutputStream()

    init {
        require(chunkBytes > 0)
    }

    @Synchronized
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val safeOffset = offset.coerceIn(0, data.size)
        val safeLength = length.coerceAtMost(data.size - safeOffset)
        if (safeLength <= 0) return
        buffer.write(data, safeOffset, safeLength)
        drainFullChunks()
    }

    @Synchronized
    fun flush() {
        drainFullChunks()
        drain()?.let(onChunk)
    }

    @Synchronized
    fun drain(): ByteArray? {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return bytes.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun clear() {
        buffer.reset()
    }

    private fun drainFullChunks() {
        while (buffer.size() >= chunkBytes) {
            val bytes = buffer.toByteArray()
            val chunk = bytes.copyOfRange(0, chunkBytes)
            val rest = bytes.copyOfRange(chunkBytes, bytes.size)
            buffer.reset()
            buffer.write(rest)
            onChunk(chunk)
        }
    }
}

internal object Pcm16Resampler {
    fun upsample16kTo24k(input: ByteArray): ByteArray {
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)
        val outputSamples = inputSamples * 3 / 2
        val output = ByteArray(outputSamples * 2)
        for (outIndex in 0 until outputSamples) {
            val sourcePosition = outIndex * 2.0 / 3.0
            val baseIndex = sourcePosition.toInt().coerceAtMost(inputSamples - 1)
            val nextIndex = (baseIndex + 1).coerceAtMost(inputSamples - 1)
            val fraction = sourcePosition - baseIndex
            val sample = sampleAt(input, baseIndex) +
                ((sampleAt(input, nextIndex) - sampleAt(input, baseIndex)) * fraction)
            writeSample(
                output,
                outIndex,
                sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
            )
        }
        return output
    }

    private fun sampleAt(bytes: ByteArray, index: Int): Int {
        val byteIndex = index * 2
        val low = bytes[byteIndex].toInt() and 0xff
        val high = bytes[byteIndex + 1].toInt()
        return (high shl 8) or low
    }

    private fun writeSample(bytes: ByteArray, index: Int, sample: Int) {
        val byteIndex = index * 2
        bytes[byteIndex] = (sample and 0xff).toByte()
        bytes[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
    }
}

internal fun openAiSessionUpdate(
    model: String,
    language: String?,
    prompt: String? = null,
): JSONObject {
    val transcription = JSONObject()
        .put("model", model)
        .put("delay", "low")
    if (!language.isNullOrBlank()) transcription.put("language", language)
    if (!prompt.isNullOrBlank()) transcription.put("prompt", prompt)
    return JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject()
                            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                            .put("transcription", transcription)
                            .put("turn_detection", JSONObject.NULL),
                    ),
                ),
        )
}

internal fun openAiLanguageFor(
    forcedLanguage: TranscriptionLanguage,
    phoneLanguageTag: String,
): String? =
    if (forcedLanguage == TranscriptionLanguage.AUTO) {
        languageCode(phoneLanguageTag)
    } else {
        forcedLanguage.openAiCode
    }

private fun elevenLabsRealtimeUrl(model: String, language: String?): String {
    val query = mutableListOf(
        "model_id=${model.urlEncoded()}",
        "audio_format=pcm_16000",
        "commit_strategy=manual",
        "include_timestamps=false",
    )
    if (!language.isNullOrBlank()) query += "language_code=${language.urlEncoded()}"
    return "wss://api.elevenlabs.io/v1/speech-to-text/realtime?${query.joinToString("&")}"
}

private fun JSONObject.errorMessage(): String {
    val nested = optJSONObject("error")
    return nested?.optString("message").orEmpty()
        .ifBlank { optString("message") }
        .ifBlank { optString("error") }
}

private fun languageCode(languageTag: String): String? {
    val language = Locale.forLanguageTag(languageTag).language.lowercase(Locale.US)
    return language.takeIf { it.length in 2..3 }
}

private fun String.urlEncoded(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name())
