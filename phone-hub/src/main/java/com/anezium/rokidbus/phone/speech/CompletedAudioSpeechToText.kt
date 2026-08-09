package com.anezium.rokidbus.phone.speech

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

data class CompletedAudioSpeechToTextInput(
    val pcm16Mono: ByteArray,
    val sampleRate: Int,
    val languageTag: String,
    val language: TranscriptionLanguage = TranscriptionLanguage.AUTO,
)

internal interface CompletedAudioSpeechToTextEngine {
    fun transcribe(input: CompletedAudioSpeechToTextInput): String
    fun cancel()
}

internal class ApiCompletedAudioSpeechToTextEngine(
    private val secrets: HubSecretStore,
    private val engine: SpeechEngine,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : CompletedAudioSpeechToTextEngine {
    private val connectionLock = Any()

    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    override fun transcribe(input: CompletedAudioSpeechToTextInput): String {
        if (cancelled) throw cancelledError()
        if (input.pcm16Mono.size < MIN_AUDIO_BYTES) {
            throw SttEngineException(
                SttError(SttErrorKind.NO_SPEECH, engine.provider.displayName, "Not enough speech audio"),
            )
        }
        if (!engine.usesCompletedAudio) {
            throw SttEngineException(
                SttError(SttErrorKind.INTERNAL, engine.provider.displayName, "Invalid buffered engine"),
            )
        }
        val model = engine.completedAudioModelId ?: throw SttEngineException(
            SttError(SttErrorKind.INTERNAL, engine.provider.displayName, "Buffered model is unavailable"),
        )
        val wav = Pcm16Wav.encode(input.pcm16Mono, input.sampleRate)
        return try {
            when (engine.provider) {
                SpeechProvider.ANDROID -> error("${engine.id} is not a buffered STT engine")
                SpeechProvider.OPENAI -> transcribeOpenAi(model, wav, input.language)
                SpeechProvider.ELEVENLABS -> transcribeElevenLabs(model, wav, input.language)
                SpeechProvider.AZURE -> transcribeAzure(wav, input.language, input.languageTag)
            }
        } catch (error: SttEngineException) {
            throw error
        } catch (failure: Throwable) {
            if (cancelled) throw cancelledError(failure)
            throw SttEngineException(
                providerError(engine.provider, failure = failure),
                failure,
            )
        } finally {
            synchronized(connectionLock) {
                activeConnection = null
            }
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(connectionLock) {
            activeConnection?.disconnect()
            activeConnection = null
        }
    }

    private fun transcribeOpenAi(
        model: String,
        wav: ByteArray,
        language: TranscriptionLanguage,
    ): String {
        val apiKey = requireApiKey(SpeechCredentialKind.OPENAI)
        val connection = createConnection(URL(OPENAI_TRANSCRIPTIONS_URL)) {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        return connection.useMultipart(SpeechProvider.OPENAI) { boundary ->
            writeField(boundary, "model", model)
            writeField(boundary, "response_format", "json")
            language.openAiCode?.let { writeField(boundary, "language", it) }
            writeField(
                boundary,
                "prompt",
                language.openAiPrompt?.let { "$OPENAI_BASE_PROMPT $it" } ?: OPENAI_BASE_PROMPT,
            )
            writeFile(
                boundary = boundary,
                name = "file",
                filename = "nexus-speech.wav",
                contentType = "audio/wav",
                bytes = wav,
            )
        }.extractTranscript(SpeechProvider.OPENAI)
    }

    private fun transcribeElevenLabs(
        model: String,
        wav: ByteArray,
        language: TranscriptionLanguage,
    ): String {
        val apiKey = requireApiKey(SpeechCredentialKind.ELEVENLABS)
        val connection = createConnection(URL(ELEVENLABS_SPEECH_TO_TEXT_URL)) {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
        }
        return connection.useMultipart(SpeechProvider.ELEVENLABS) { boundary ->
            writeField(boundary, "model_id", model)
            language.elevenLabsCode?.let { writeField(boundary, "language_code", it) }
            writeFile(
                boundary = boundary,
                name = "file",
                filename = "nexus-speech.wav",
                contentType = "audio/wav",
                bytes = wav,
            )
        }.extractTranscript(SpeechProvider.ELEVENLABS)
    }

    private fun transcribeAzure(
        wav: ByteArray,
        language: TranscriptionLanguage,
        languageTag: String,
    ): String {
        val region = secrets.azureRegion().orEmpty()
        if (!isValidAzureRegion(region)) {
            throw SttEngineException(
                SttError(
                    SttErrorKind.PROVIDER,
                    SpeechProvider.AZURE.displayName,
                    "Azure region is invalid",
                ),
            )
        }
        val apiKey = requireApiKey(SpeechCredentialKind.AZURE)
        val locale = language.azureLocale ?: azureLocaleFromTag(languageTag)
        val url = URL(
            "https://$region.stt.speech.microsoft.com/" +
                "speech/recognition/conversation/cognitiveservices/v1" +
                "?language=$locale&format=simple",
        )
        val connection = createConnection(url) {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", apiKey)
            setRequestProperty("Content-Type", "audio/wav; codecs=audio/pcm; samplerate=16000")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (cancelled) throw cancelledError()
            connection.outputStream.buffered().use { it.write(wav) }
            val (status, body) = connection.readResponse()
            if (status !in 200..299) {
                throw SttEngineException(
                    providerError(
                        SpeechProvider.AZURE,
                        statusCode = status,
                        providerMessage = responseErrorMessage(body),
                    ),
                )
            }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw SttEngineException(
                    SttError(
                        SttErrorKind.PROVIDER,
                        SpeechProvider.AZURE.displayName,
                        "Provider returned an invalid response",
                    ),
                )
            }
            return when (json.optString("RecognitionStatus")) {
                "Success" -> json.optString("DisplayText").trim().ifBlank {
                    throw SttEngineException(
                        SttError(
                            SttErrorKind.NO_SPEECH,
                            SpeechProvider.AZURE.displayName,
                            "Provider did not recognize speech",
                        ),
                    )
                }
                "NoMatch" -> throw SttEngineException(
                    SttError(
                        SttErrorKind.NO_SPEECH,
                        SpeechProvider.AZURE.displayName,
                        "Provider did not recognize speech",
                    ),
                )
                else -> throw SttEngineException(
                    providerError(
                        SpeechProvider.AZURE,
                        providerMessage = json.optString("RecognitionStatus"),
                    ),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requireApiKey(kind: SpeechCredentialKind): String {
        if (cancelled) throw cancelledError()
        return secrets.apiKey(kind)?.trim()?.takeIf { it.isNotBlank() }
            ?: throw SttEngineException(
                SttError(SttErrorKind.AUTH, engine.provider.displayName, "Provider credential is missing"),
            )
    }

    private fun createConnection(
        url: URL,
        configure: HttpURLConnection.() -> Unit,
    ): HttpURLConnection {
        if (cancelled) throw cancelledError()
        val connection = connectionFactory(url).apply(configure)
        synchronized(connectionLock) {
            if (cancelled) {
                connection.disconnect()
                throw cancelledError()
            }
            activeConnection = connection
        }
        return connection
    }

    private fun HttpURLConnection.useMultipart(
        provider: SpeechProvider,
        write: MultipartWriter.(String) -> Unit,
    ): String {
        val boundary = "----NexusSpeech${UUID.randomUUID().toString().replace("-", "")}"
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            if (cancelled) throw cancelledError()
            MultipartWriter(outputStream.buffered()).write(boundary, write)
            val (status, body) = readResponse()
            if (status !in 200..299) {
                throw SttEngineException(
                    providerError(
                        provider,
                        statusCode = status,
                        providerMessage = responseErrorMessage(body),
                    ),
                )
            }
            return body
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.readResponse(): Pair<Int, String> {
        if (cancelled) throw cancelledError()
        val status = responseCode
        val body = runCatching {
            (if (status in 200..299) inputStream else errorStream ?: inputStream)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrDefault("")
        if (cancelled) throw cancelledError()
        return status to body
    }

    private fun String.extractTranscript(provider: SpeechProvider): String {
        val json = runCatching { JSONObject(this) }.getOrElse {
            throw SttEngineException(
                SttError(
                    SttErrorKind.PROVIDER,
                    provider.displayName,
                    "Provider returned an invalid response",
                ),
            )
        }
        json.optJSONObject("error")?.let { error ->
            throw SttEngineException(
                providerError(provider, providerMessage = error.optString("message")),
            )
        }
        return json.optString("text").trim().ifBlank {
            throw SttEngineException(
                SttError(
                    SttErrorKind.NO_SPEECH,
                    provider.displayName,
                    "Provider did not recognize speech",
                ),
            )
        }
    }

    private fun azureLocaleFromTag(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        val language = locale.language.lowercase(Locale.US)
        if (language.isBlank()) return DEFAULT_AZURE_LOCALE
        if (language == "yue") return "zh-HK"
        if (language == "zh") {
            return when {
                locale.country.equals("HK", ignoreCase = true) -> "zh-HK"
                locale.script.equals("Hant", ignoreCase = true) ||
                    locale.country.equals("TW", ignoreCase = true) -> "zh-TW"
                else -> "zh-CN"
            }
        }
        if (locale.country.isNotBlank()) return "$language-${locale.country.uppercase(Locale.US)}"
        return COMMON_AZURE_LOCALES[language] ?: DEFAULT_AZURE_LOCALE
    }

    private fun cancelledError(cause: Throwable? = null): SttEngineException =
        SttEngineException(
            SttError(SttErrorKind.CANCELLED, engine.provider.displayName, "Speech session was cancelled"),
            cause,
        )

    companion object {
        private const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val ELEVENLABS_SPEECH_TO_TEXT_URL =
            "https://api.elevenlabs.io/v1/speech-to-text"
        private const val OPENAI_BASE_PROMPT =
            "Transcribe short speech captured from smart glasses. Preserve the spoken language."
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MIN_AUDIO_BYTES = 3_200
        private const val DEFAULT_AZURE_LOCALE = "en-US"
        private val COMMON_AZURE_LOCALES = mapOf(
            "en" to "en-US",
            "fr" to "fr-FR",
            "de" to "de-DE",
            "es" to "es-ES",
            "it" to "it-IT",
            "pt" to "pt-BR",
            "ja" to "ja-JP",
            "ko" to "ko-KR",
            "nl" to "nl-NL",
            "pl" to "pl-PL",
            "ru" to "ru-RU",
        )
    }
}

internal class BufferedSttSession(
    private val engine: SpeechEngine,
    private val language: TranscriptionLanguage,
    private val languageTag: String,
    private val transcriber: CompletedAudioSpeechToTextEngine,
    private val executor: Executor,
    private val listener: SttSessionListener,
) : SttSession {
    private val lock = Any()
    private var buffer = ByteArrayOutputStream()
    private var started = false
    private var finished = false

    @Volatile private var cancelled = false

    override fun start(): Boolean {
        synchronized(lock) {
            if (cancelled || started) return false
            started = true
        }
        listener.onReady()
        return true
    }

    override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        if (cancelled || length <= 0) return
        val safeOffset = offset.coerceIn(0, data.size)
        val safeLength = length.coerceAtMost(data.size - safeOffset)
        if (safeLength <= 0) return
        synchronized(lock) {
            if (cancelled || finished) return
            buffer.write(data, safeOffset, safeLength)
        }
    }

    override fun finishAudio() {
        val pcm = synchronized(lock) {
            if (cancelled || finished || !started) return
            finished = true
            buffer.toByteArray().also { buffer.reset() }
        }
        executor.execute {
            if (cancelled) return@execute
            try {
                val transcript = transcriber.transcribe(
                    CompletedAudioSpeechToTextInput(
                        pcm16Mono = pcm,
                        sampleRate = SAMPLE_RATE_HZ,
                        languageTag = languageTag,
                        language = language,
                    ),
                )
                if (!cancelled) listener.onFinal(transcript)
            } catch (error: SttEngineException) {
                if (!cancelled) listener.onError(error.error)
            } catch (failure: Throwable) {
                if (!cancelled) {
                    listener.onError(
                        SttError(
                            SttErrorKind.INTERNAL,
                            engine.provider.displayName,
                            "Buffered speech engine failed internally",
                        ),
                    )
                }
            }
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(lock) {
            buffer.reset()
        }
        transcriber.cancel()
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
    }
}

internal class MultipartWriter(
    private val stream: OutputStream,
) {
    private lateinit var output: DataOutputStream

    fun write(boundary: String, block: MultipartWriter.(String) -> Unit) {
        DataOutputStream(stream).use { dataStream ->
            output = dataStream
            block(boundary)
            writeAscii("--$boundary--\r\n")
            output.flush()
        }
    }

    fun writeField(boundary: String, name: String, value: String) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.write(value.toByteArray(Charsets.UTF_8))
        writeAscii("\r\n")
    }

    fun writeFile(
        boundary: String,
        name: String,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        writeAscii("Content-Type: $contentType\r\n\r\n")
        output.write(bytes)
        writeAscii("\r\n")
    }

    private fun writeAscii(value: String) {
        output.write(value.toByteArray(Charsets.US_ASCII))
    }
}

internal object Pcm16Wav {
    fun encode(pcm16Mono: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm16Mono.size
        val byteRate = sampleRate * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream(WAV_HEADER_BYTES + dataSize).apply {
            writeAscii("RIFF")
            writeIntLe(36 + dataSize)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeIntLe(16)
            writeShortLe(1)
            writeShortLe(CHANNEL_COUNT)
            writeIntLe(sampleRate)
            writeIntLe(byteRate)
            writeShortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            writeShortLe(BITS_PER_SAMPLE)
            writeAscii("data")
            writeIntLe(dataSize)
            write(pcm16Mono)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private const val WAV_HEADER_BYTES = 44
    private const val CHANNEL_COUNT = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16
}

internal fun isValidAzureRegion(region: String): Boolean =
    region.matches(Regex("^[a-z0-9-]+$"))

private fun responseErrorMessage(body: String): String {
    if (body.isBlank()) return ""
    return runCatching {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { json.optString("message") }
            .ifBlank { json.optString("RecognitionStatus") }
    }.getOrDefault("")
}
