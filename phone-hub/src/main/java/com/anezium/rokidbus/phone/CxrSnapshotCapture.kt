package com.anezium.rokidbus.phone

import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.callbacks.IImageStreamCbk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface CxrSnapshotLink {
    fun setImageCallback(callback: IImageStreamCbk?)
    fun takePhoto(width: Int, height: Int, quality: Int): Boolean
}

internal class CxrLinkSnapshotAdapter(
    private val link: CXRLink,
) : CxrSnapshotLink {
    override fun setImageCallback(callback: IImageStreamCbk?) {
        link.setCXRImageCbk(callback)
    }

    override fun takePhoto(width: Int, height: Int, quality: Int): Boolean =
        link.takePhoto(width, height, quality)
}

internal class SnapshotCaptureTimeoutException(
    cause: Throwable?,
) : IllegalStateException("Glasses photo capture timed out.", cause)

internal class SnapshotLinkDownCancellationException :
    CancellationException("Glasses link went down during photo capture.")

/**
 * Owns the process-wide CXR image callback while one still is in flight. CXR exposes a single
 * callback slot, so captures are serialized even if a future caller bypasses the hub's BUSY gate.
 */
internal class CxrSnapshotCapture(
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    private val firstAttemptCooldownMs: Long = DEFAULT_FIRST_ATTEMPT_COOLDOWN_MS,
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
) {
    private val captureMutex = Mutex()

    suspend fun capture(
        link: CxrSnapshotLink,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        quality: Int = DEFAULT_QUALITY,
    ): ByteArray = captureMutex.withLock {
        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            delay(if (attempt == 1) firstAttemptCooldownMs else retryCooldownMs)
            try {
                return@withLock captureAttempt(link, width, height, quality)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                lastError = cancelled
            } catch (failure: Throwable) {
                lastError = failure
            } finally {
                runCatching { link.setImageCallback(null) }
            }
        }
        if (lastError is TimeoutCancellationException) {
            throw SnapshotCaptureTimeoutException(lastError)
        }
        throw lastError ?: IllegalStateException("Glasses photo capture failed.")
    }

    private suspend fun captureAttempt(
        link: CxrSnapshotLink,
        width: Int,
        height: Int,
        quality: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        withTimeout(attemptTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val callback = object : IImageStreamCbk {
                    override fun onImageReceived(data: ByteArray) {
                        if (!completed.compareAndSet(false, true)) return
                        runCatching { link.setImageCallback(null) }
                        continuation.resume(data)
                    }

                    override fun onImageError(code: Int, msg: String?) {
                        if (!completed.compareAndSet(false, true)) return
                        runCatching { link.setImageCallback(null) }
                        continuation.resumeWithException(
                            IllegalStateException(
                                "Glasses photo capture failed ($code): ${msg ?: "unknown"}",
                            ),
                        )
                    }
                }

                link.setImageCallback(callback)
                val requested = runCatching {
                    link.takePhoto(width, height, quality)
                }.getOrElse { failure ->
                    if (completed.compareAndSet(false, true)) {
                        runCatching { link.setImageCallback(null) }
                        continuation.resumeWithException(failure)
                    }
                    return@suspendCancellableCoroutine
                }
                if (!requested && completed.compareAndSet(false, true)) {
                    runCatching { link.setImageCallback(null) }
                    continuation.resumeWithException(
                        IllegalStateException("Glasses photo request was rejected."),
                    )
                }
                continuation.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) {
                        runCatching { link.setImageCallback(null) }
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_ATTEMPTS = 2
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 9_000L
        const val DEFAULT_FIRST_ATTEMPT_COOLDOWN_MS = 900L
        const val DEFAULT_RETRY_COOLDOWN_MS = 1_200L
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 768
        const val DEFAULT_QUALITY = 80
    }
}
