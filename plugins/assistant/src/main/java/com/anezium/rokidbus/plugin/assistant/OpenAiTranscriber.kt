package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

internal object WavPcmEncoder {
    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16

    fun encode(pcm: ByteArray, format: NexusAudioFormat): ByteArray {
        require(format.sampleRate > 0) { "Invalid audio sample rate." }
        require(format.channels in 1..2) { "Unsupported audio channel count." }
        require(format.encoding.normalizedEncoding() in PCM_16_LE_ENCODINGS) {
            "Unsupported audio encoding: ${format.encoding}"
        }
        val blockAlign = format.channels * (BITS_PER_SAMPLE / 8)
        require(pcm.size % blockAlign == 0) { "PCM data is not sample-aligned." }

        return ByteBuffer.allocate(HEADER_BYTES + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(format.channels.toShort())
                putInt(format.sampleRate)
                putInt(format.sampleRate * blockAlign)
                putShort(blockAlign.toShort())
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
                put(pcm)
            }
            .array()
    }

    private fun String.normalizedEncoding(): String =
        lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private val PCM_16_LE_ENCODINGS = setOf("pcm16le", "s16le", "pcm16", "signed16le")
}

class OpenAiTranscriber(
    private val apiKeyProvider: () -> String?,
    private val endpointProvider: () -> String = { DEFAULT_ENDPOINT },
) {
    suspend fun transcribe(pcm: ByteArray, format: NexusAudioFormat): String {
        if (pcm.isEmpty()) return ""
        val wav = WavPcmEncoder.encode(pcm, format)
        return try {
            transcribeWav(wav, PRIMARY_MODEL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (primaryFailure: Throwable) {
            try {
                transcribeWav(wav, FALLBACK_MODEL)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fallbackFailure: Throwable) {
                val primary = primaryFailure.conciseProviderMessage("primary model failed")
                val fallback = fallbackFailure.conciseProviderMessage("fallback model failed")
                throw IllegalStateException("Transcription failed: $primary; fallback: $fallback")
            }
        }
    }

    private suspend fun transcribeWav(wav: ByteArray, model: String): String =
        withContext(Dispatchers.IO) {
            val boundary = "NexusAssistant-${UUID.randomUUID()}"
            val body = multipartBody(boundary, model, wav)
            val endpoint = endpointProvider().trim().ifBlank { DEFAULT_ENDPOINT }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Authorization", "Bearer ${apiKeyProvider().orEmpty()}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            try {
                connection.outputStream.use { output -> output.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException(
                        "OpenAI transcription failed ($status): ${connection.safeErrorBody()}",
                    )
                }
                val response = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                JSONObject(response).optString("text").trim()
            } finally {
                connection.disconnect()
            }
        }

    private fun multipartBody(boundary: String, model: String, wav: ByteArray): ByteArray =
        ByteArrayOutputStream(wav.size + 512).use { body ->
            body.writeUtf8("--$boundary\r\n")
            body.writeUtf8("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            body.writeUtf8("$model\r\n")
            body.writeUtf8("--$boundary\r\n")
            body.writeUtf8(
                "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n",
            )
            body.writeUtf8("Content-Type: audio/wav\r\n\r\n")
            body.write(wav)
            body.writeUtf8("\r\n--$boundary--\r\n")
            body.toByteArray()
        }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val PRIMARY_MODEL = "gpt-4o-transcribe"
        const val FALLBACK_MODEL = "whisper-1"
    }
}

private fun ByteArrayOutputStream.writeUtf8(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}
