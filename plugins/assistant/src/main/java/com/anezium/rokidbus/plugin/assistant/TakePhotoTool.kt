package com.anezium.rokidbus.plugin.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class TakePhotoToolSession(
    val requestId: String,
    val generation: Long,
)

internal sealed interface TakePhotoCaptureResult {
    data class Captured(val jpeg: ByteArray) : TakePhotoCaptureResult
    data class Failed(val code: String) : TakePhotoCaptureResult
}

internal data class TakePhotoToolOutcome(
    val requestId: String?,
    val toolName: String,
    val outcome: String,
    val byteCount: Int,
    val width: Int,
    val height: Int,
    val captureMs: Long,
    val processMs: Long,
    val totalMs: Long,
)

internal interface TakePhotoToolCapabilities {
    fun currentSession(): TakePhotoToolSession?
    fun isSessionActive(session: TakePhotoToolSession): Boolean
    fun hasCameraGrant(): Boolean
    fun isGlassesConnected(): Boolean
    fun isCameraBusy(): Boolean
    fun showTransient(message: String)
    suspend fun captureSnapshotJpeg(): TakePhotoCaptureResult
    fun markPhotoCaptured(session: TakePhotoToolSession)
    fun retainPhoto(session: TakePhotoToolSession, jpeg: ByteArray)
    fun logOutcome(outcome: TakePhotoToolOutcome)
}

internal class TakePhotoTool(
    private val capabilities: TakePhotoToolCapabilities,
    private val elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AssistantToolDefinition {
    override val name: String = TAKE_PHOTO_TOOL_NAME
    override val description: String = TAKE_PHOTO_TOOL_DESCRIPTION
    override val parametersSchema: AssistantToolJsonSchema = TAKE_PHOTO_PARAMETERS_SCHEMA
    override val sideEffecting: Boolean = true
    override val progressLabel: String? = null
    override val executionFailureCode: String = TOOL_ERROR_CAPTURE_FAILED

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.provider.supportsVision && context.session.active

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = runCatching {
            JSONObject(argumentsJson.ifBlank { "{}" })
        }.getOrNull() ?: return AssistantToolValidation.Invalid()
        return if (arguments.length() == 0) {
            AssistantToolValidation.Valid(arguments)
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult {
        val startedAt = elapsedClock()
        val session = capabilities.currentSession()

        fun error(
            code: String,
            captureMs: Long = 0L,
            processMs: Long = 0L,
        ): AssistantToolResult.Error {
            capabilities.logOutcome(
                TakePhotoToolOutcome(
                    requestId = session?.requestId,
                    toolName = name,
                    outcome = code,
                    byteCount = 0,
                    width = 0,
                    height = 0,
                    captureMs = captureMs,
                    processMs = processMs,
                    totalMs = elapsedClock() - startedAt,
                ),
            )
            return AssistantToolResult.Error(code)
        }

        if (session == null || !capabilities.isSessionActive(session)) {
            return error(TOOL_ERROR_CANCELLED)
        }
        if (!capabilities.hasCameraGrant()) return error(TOOL_ERROR_NOT_AUTHORIZED)
        if (!capabilities.isGlassesConnected()) {
            return error(TOOL_ERROR_GLASSES_DISCONNECTED)
        }
        if (capabilities.isCameraBusy()) return error(TOOL_ERROR_CAMERA_BUSY)

        currentCoroutineContext().ensureActive()
        capabilities.showTransient(TAKE_PHOTO_PROGRESS_LABEL)
        currentCoroutineContext().ensureActive()
        if (!capabilities.isSessionActive(session)) {
            throw CancellationException("Assistant generation is stale.")
        }

        val captureStartedAt = elapsedClock()
        val capture = try {
            withTimeoutOrNull(SNAPSHOT_CAPTURE_TIMEOUT_MS) {
                capabilities.captureSnapshotJpeg()
            } ?: return error(
                code = TOOL_ERROR_CAPTURE_FAILED,
                captureMs = elapsedClock() - captureStartedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return error(
                code = TOOL_ERROR_CAPTURE_FAILED,
                captureMs = elapsedClock() - captureStartedAt,
            )
        }
        if (capture is TakePhotoCaptureResult.Failed) {
            return error(
                code = capture.code,
                captureMs = elapsedClock() - captureStartedAt,
            )
        }
        capture as TakePhotoCaptureResult.Captured
        val captureMs = elapsedClock() - captureStartedAt
        capabilities.markPhotoCaptured(session)

        currentCoroutineContext().ensureActive()
        val processStartedAt = elapsedClock()
        val image = withContext(Dispatchers.Default) {
            prepareToolImage(capture.jpeg)
        } ?: return error(
            code = TOOL_ERROR_CAPTURE_FAILED,
            captureMs = captureMs,
            processMs = elapsedClock() - processStartedAt,
        )
        val processMs = elapsedClock() - processStartedAt
        currentCoroutineContext().ensureActive()
        if (!capabilities.isSessionActive(session)) {
            throw CancellationException("Assistant generation is stale.")
        }
        capabilities.retainPhoto(session, image.jpeg)

        capabilities.logOutcome(
            TakePhotoToolOutcome(
                requestId = session.requestId,
                toolName = name,
                outcome = "ok",
                byteCount = image.byteCount,
                width = image.width,
                height = image.height,
                captureMs = captureMs,
                processMs = processMs,
                totalMs = elapsedClock() - startedAt,
            ),
        )
        return AssistantToolResult.Image(
            mimeType = "image/jpeg",
            base64 = image.base64,
        )
    }

    private fun prepareToolImage(jpeg: ByteArray): PreparedToolImage? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val sourceLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (sourceLongEdge / (sampleSize * 2) >= MAX_TOOL_IMAGE_LONG_EDGE_PX) {
            sampleSize *= 2
        }
        var bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        try {
            val decodedLongEdge = maxOf(bitmap.width, bitmap.height)
            if (decodedLongEdge > MAX_TOOL_IMAGE_LONG_EDGE_PX) {
                val scale = MAX_TOOL_IMAGE_LONG_EDGE_PX.toFloat() / decodedLongEdge
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, TOOL_IMAGE_JPEG_QUALITY, output)) {
                    return null
                }
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_TOOL_IMAGE_JPEG_BYTES) {
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (base64.length > MAX_TOOL_IMAGE_BASE64_CHARS) return null
                    return PreparedToolImage(
                        jpeg = bytes,
                        base64 = base64,
                        byteCount = bytes.size,
                        width = bitmap.width,
                        height = bitmap.height,
                    )
                }

                val sizeScale =
                    (sqrt(MAX_TOOL_IMAGE_JPEG_BYTES.toDouble() / bytes.size) * 0.9)
                        .coerceIn(0.5, 0.9)
                val nextWidth = (bitmap.width * sizeScale).roundToInt().coerceAtLeast(1)
                val nextHeight = (bitmap.height * sizeScale).roundToInt().coerceAtLeast(1)
                if (nextWidth == bitmap.width && nextHeight == bitmap.height) return null
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    nextWidth,
                    nextHeight,
                    true,
                )
                if (scaled === bitmap) return null
                bitmap.recycle()
                bitmap = scaled
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAKE_PHOTO_PROGRESS_LABEL = "Photo\u2026"
        const val SNAPSHOT_CAPTURE_TIMEOUT_MS = 8_000L
        const val MAX_TOOL_IMAGE_LONG_EDGE_PX = 1_024
        const val TOOL_IMAGE_JPEG_QUALITY = 80
        const val MAX_TOOL_IMAGE_BASE64_CHARS = 1_500_000
        const val MAX_TOOL_IMAGE_JPEG_BYTES = MAX_TOOL_IMAGE_BASE64_CHARS / 4 * 3
    }
}

private data class PreparedToolImage(
    val jpeg: ByteArray,
    val base64: String,
    val byteCount: Int,
    val width: Int,
    val height: Int,
)

internal const val TAKE_PHOTO_TOOL_DESCRIPTION =
    "Capture one current point-of-view photo from the user's Rokid glasses. " +
        "Call this only when answering the current request requires seeing the user's " +
        "current physical scene. Do not call it for web images, questions about camera " +
        "behavior or settings, discussion of a previous photo, or questions answerable " +
        "from text or web information."

internal val TAKE_PHOTO_PARAMETERS_SCHEMA = AssistantToolJsonSchema(
    """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
)

internal fun snapshotToolErrorCode(error: NexusSnapshotError): String = when (error) {
    NexusSnapshotError.BUSY -> TOOL_ERROR_CAMERA_BUSY
    NexusSnapshotError.LINK_DOWN -> TOOL_ERROR_GLASSES_DISCONNECTED
    NexusSnapshotError.CANCELLED -> TOOL_ERROR_CANCELLED
    NexusSnapshotError.TIMEOUT,
    NexusSnapshotError.CAPTURE_FAILED,
    NexusSnapshotError.ERROR,
    -> TOOL_ERROR_CAPTURE_FAILED
}

internal fun snapshotStartToolErrorCode(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> TOOL_ERROR_NOT_AUTHORIZED
    NexusSdkResult.NOT_REGISTERED -> TOOL_ERROR_GLASSES_DISCONNECTED
    NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> TOOL_ERROR_CAMERA_BUSY
    else -> TOOL_ERROR_CAPTURE_FAILED
}
