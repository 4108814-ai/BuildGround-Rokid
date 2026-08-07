package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolRegistryTest {
    @Test
    fun `multiple registered tools are available without provider changes`() {
        val registry = AssistantToolRegistry(
            definitions = listOf(
                TestAssistantTool("first_tool"),
                TestAssistantTool(
                    name = "second_tool",
                    available = { context ->
                        context.session.active && "notes" in context.session.grantedCapabilities
                    },
                ),
            ),
            sessionContext = {
                AssistantToolSessionContext(
                    active = true,
                    grantedCapabilities = setOf("notes"),
                )
            },
        )

        val available = registry.availableDefinitions(TOOLS_WITHOUT_VISION)

        assertEquals(listOf("first_tool", "second_tool"), available.map { it.name })
        assertTrue(available.all { it.parametersSchema.toJsonObject().has("type") })
    }

    @Test
    fun `provider without tool support receives no definitions`() {
        val registry = AssistantToolRegistry(listOf(TestAssistantTool("fake_tool")))

        val available = registry.availableDefinitions(
            AssistantProviderFeatures(supportsTools = false, supportsVision = true),
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `phase executes at most three calls`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { call, _ ->
                executions += 1
                AssistantToolResult.Json("result:${call.callId}")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val results = (1..4).map { index ->
            phase.execute(AssistantToolCall("call-$index", tool.name, "{}"))
        }

        assertEquals(3, executions)
        assertEquals(
            AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED),
            results.last(),
        )
    }

    @Test
    fun `side effecting tool executes at most once per phase`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "save_note",
            sideEffecting = true,
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("saved")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val first = phase.execute(AssistantToolCall("call-1", tool.name, "{}"))
        val second = phase.execute(AssistantToolCall("call-2", tool.name, "{}"))

        assertEquals(AssistantToolResult.Json("saved"), first)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), second)
        assertEquals(1, executions)
    }

    @Test
    fun `duplicate call id returns memoized result without reexecution`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { call, _ ->
                executions += 1
                AssistantToolResult.Json("first:${call.argumentsJson}")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val first = phase.execute(AssistantToolCall("same-id", tool.name, "{}"))
        val duplicate = phase.execute(
            AssistantToolCall("same-id", tool.name, "not-the-original-call"),
        )

        assertEquals(first, duplicate)
        assertEquals(1, executions)
    }

    @Test
    fun `invalid arguments return structured error without execution`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("unexpected")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val malformed = phase.execute(AssistantToolCall("bad-json", tool.name, "not-json"))
        val unexpected = phase.execute(
            AssistantToolCall("bad-property", tool.name, """{"extra":true}"""),
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), malformed)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), unexpected)
        assertEquals(0, executions)
    }

    @Test
    fun `unavailable tool called anyway returns invalid call`() = runTest {
        var executions = 0
        val unavailable = TestAssistantTool(
            name = "lookup_note",
            available = { false },
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("unexpected")
            },
        )
        val phase = AssistantToolRegistry(listOf(unavailable))
            .newExecutionPhase(TOOLS_WITHOUT_VISION)

        val result = phase.execute(AssistantToolCall("call-1", unavailable.name, "{}"))

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), result)
        assertEquals(0, executions)
    }

    @Test
    fun `executor exception maps to a tool scoped failure`() = runTest {
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { _, _ -> error("database unavailable") },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val result = phase.execute(AssistantToolCall("call-1", tool.name, "{}"))

        assertEquals(AssistantToolResult.Error("lookup_note_failed"), result)
    }

    @Test
    fun `take photo exception keeps capture failed wire code`() = runTest {
        val phase = testToolRegistry(
            executor = { error("camera failed") },
        ).newExecutionPhase(
            AssistantProviderFeatures(supportsTools = true, supportsVision = true),
        )

        val result = phase.execute(
            AssistantToolCall("call-photo", TAKE_PHOTO_TOOL_NAME, "{}"),
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED), result)
    }

    private companion object {
        val TOOLS_WITHOUT_VISION = AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = false,
        )
    }
}
