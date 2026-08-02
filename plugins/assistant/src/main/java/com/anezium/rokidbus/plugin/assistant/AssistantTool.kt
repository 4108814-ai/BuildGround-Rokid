package com.anezium.rokidbus.plugin.assistant

data class AssistantToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface AssistantToolResult {
    data class Image(
        val mimeType: String,
        val base64: String,
    ) : AssistantToolResult

    data class Error(
        val code: String,
    ) : AssistantToolResult {
        init {
            require(code in ASSISTANT_TOOL_ERROR_CODES) {
                "Unsupported assistant tool error code."
            }
        }
    }
}

fun interface AssistantToolExecutor {
    suspend fun execute(call: AssistantToolCall): AssistantToolResult
}

internal const val TAKE_PHOTO_TOOL_NAME = "take_photo"
internal const val TOOL_ERROR_NOT_AUTHORIZED = "not_authorized"
internal const val TOOL_ERROR_GLASSES_DISCONNECTED = "glasses_disconnected"
internal const val TOOL_ERROR_CAMERA_BUSY = "camera_busy"
internal const val TOOL_ERROR_ALREADY_USED = "already_used"
internal const val TOOL_ERROR_CANCELLED = "cancelled"
internal const val TOOL_ERROR_CAPTURE_FAILED = "capture_failed"
internal const val TOOL_ERROR_INVALID_CALL = "invalid_call"

internal val ASSISTANT_TOOL_ERROR_CODES = setOf(
    TOOL_ERROR_NOT_AUTHORIZED,
    TOOL_ERROR_GLASSES_DISCONNECTED,
    TOOL_ERROR_CAMERA_BUSY,
    TOOL_ERROR_ALREADY_USED,
    TOOL_ERROR_CANCELLED,
    TOOL_ERROR_CAPTURE_FAILED,
    TOOL_ERROR_INVALID_CALL,
)
