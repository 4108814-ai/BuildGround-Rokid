package com.anezium.rokidbus.phone.speech

import java.net.SocketTimeoutException
import java.util.Locale

enum class SttErrorKind {
    SOURCE_UNAVAILABLE,
    NO_SPEECH,
    AUTH,
    QUOTA_RATE,
    NETWORK,
    TIMEOUT,
    UNSUPPORTED_LANGUAGE,
    CANCELLED,
    PROVIDER,
    INTERNAL,
}

data class SttError(
    val kind: SttErrorKind,
    val providerLabel: String?,
    val detail: String?,
)

interface SttSession {
    fun start(): Boolean
    fun acceptPcm(data: ByteArray, offset: Int, length: Int)
    fun finishAudio()
    fun cancel()
}

internal interface SttStartFailureSource {
    val startFailure: SttError?
}

interface SttSessionListener {
    fun onReady()
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: SttError)
}

internal class SttEngineException(
    val error: SttError,
    cause: Throwable? = null,
) : RuntimeException(error.kind.name, cause)

internal fun providerError(
    provider: SpeechProvider,
    statusCode: Int? = null,
    providerMessage: String? = null,
    failure: Throwable? = null,
): SttError {
    val normalized = providerMessage.orEmpty().lowercase(Locale.US)
    val kind = when {
        failure is SocketTimeoutException -> SttErrorKind.TIMEOUT
        statusCode == 401 || statusCode == 403 ||
            "auth" in normalized || "api key" in normalized || "unauthorized" in normalized ->
            SttErrorKind.AUTH
        statusCode == 408 || statusCode == 504 || "timeout" in normalized ->
            SttErrorKind.TIMEOUT
        statusCode == 429 || "quota" in normalized || "rate" in normalized ||
            "throttl" in normalized || "resource_exhausted" in normalized ->
            SttErrorKind.QUOTA_RATE
        "language" in normalized && ("unsupported" in normalized || "not supported" in normalized) ->
            SttErrorKind.UNSUPPORTED_LANGUAGE
        "no speech" in normalized || "no match" in normalized ||
            "insufficient_audio" in normalized ->
            SttErrorKind.NO_SPEECH
        failure is java.io.IOException -> SttErrorKind.NETWORK
        statusCode != null -> SttErrorKind.PROVIDER
        normalized.isNotBlank() -> SttErrorKind.PROVIDER
        else -> SttErrorKind.INTERNAL
    }
    val detail = when (kind) {
        SttErrorKind.AUTH -> "Provider authentication failed"
        SttErrorKind.QUOTA_RATE -> "Provider quota or rate limit reached"
        SttErrorKind.NETWORK -> "Provider network request failed"
        SttErrorKind.TIMEOUT -> "Provider request timed out"
        SttErrorKind.UNSUPPORTED_LANGUAGE -> "Provider does not support the selected language"
        SttErrorKind.NO_SPEECH -> "Provider did not recognize speech"
        SttErrorKind.PROVIDER -> statusCode?.let { "Provider request failed (HTTP $it)" }
            ?: "Provider request failed"
        SttErrorKind.INTERNAL -> "Speech engine failed internally"
        else -> null
    }
    return SttError(kind, provider.displayName, detail)
}
