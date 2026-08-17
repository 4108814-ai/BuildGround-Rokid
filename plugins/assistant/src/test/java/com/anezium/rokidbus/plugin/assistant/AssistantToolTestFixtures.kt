package com.anezium.rokidbus.plugin.assistant

import org.json.JSONObject

internal class TestAssistantTool(
    override val name: String,
    override val description: String = "Test tool $name.",
    override val parametersSchema: AssistantToolJsonSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
    ),
    override val sideEffecting: Boolean = false,
    override val progressLabel: String? = null,
    override val retiresProgressOnSuccess: Boolean = false,
    override val executionFailureCode: String = "${name}_failed",
    private val available: (AssistantToolAvailabilityContext) -> Boolean = { true },
    private val validator: (String) -> AssistantToolValidation = ::emptyObjectValidation,
    private val executor: suspend (AssistantToolCall, JSONObject) -> AssistantToolResult =
        { _, _ -> AssistantToolResult.Json("{}") },
) : AssistantToolDefinition {
    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        available(context)

    override fun validate(argumentsJson: String): AssistantToolValidation =
        validator(argumentsJson)

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = executor(call, arguments)
}

internal fun testTakePhotoTool(
    executor: suspend (AssistantToolCall) -> AssistantToolResult,
): AssistantToolDefinition = TestAssistantTool(
    name = TAKE_PHOTO_TOOL_NAME,
    description = TAKE_PHOTO_TOOL_DESCRIPTION,
    parametersSchema = TAKE_PHOTO_PARAMETERS_SCHEMA,
    sideEffecting = true,
    progressLabel = null,
    executionFailureCode = TOOL_ERROR_CAPTURE_FAILED,
    available = { context -> context.provider.supportsVision && context.session.active },
    executor = { call, _ -> executor(call) },
)

internal fun testToolRegistry(
    executor: suspend (AssistantToolCall) -> AssistantToolResult,
    vararg additionalTools: AssistantToolDefinition,
    sessionContext: () -> AssistantToolSessionContext = {
        AssistantToolSessionContext(active = true)
    },
): AssistantToolRegistry = AssistantToolRegistry(
    listOf(testTakePhotoTool(executor)) + additionalTools,
    sessionContext = sessionContext,
)

internal fun unusedToolRegistry(): AssistantToolRegistry = testToolRegistry(
    executor = { error("Tool execution was not expected.") },
)

private fun emptyObjectValidation(argumentsJson: String): AssistantToolValidation {
    val arguments = runCatching {
        JSONObject(argumentsJson.ifBlank { "{}" })
    }.getOrNull() ?: return AssistantToolValidation.Invalid()
    return if (arguments.length() == 0) {
        AssistantToolValidation.Valid(arguments)
    } else {
        AssistantToolValidation.Invalid()
    }
}
