package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusInkProblem
import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class RenderInkPageToolSession(
    val requestId: String,
    val generation: Long,
)

internal sealed interface RenderInkPageShowResult {
    data object Shown : RenderInkPageShowResult
    data class Rejected(val problems: List<NexusInkProblem>) : RenderInkPageShowResult
    data class Failed(val code: String) : RenderInkPageShowResult
}

internal interface RenderInkPageToolCapabilities {
    fun currentSession(): RenderInkPageToolSession?
    fun isSessionActive(session: RenderInkPageToolSession): Boolean
    fun supportsInkSurface(): Boolean

    suspend fun showInkPage(
        session: RenderInkPageToolSession,
        page: String,
        data: JSONObject?,
    ): RenderInkPageShowResult

    fun markInkShown(session: RenderInkPageToolSession): Boolean
}

internal class RenderInkPageTool(
    private val capabilities: RenderInkPageToolCapabilities,
) : AssistantToolDefinition {
    override val name: String = RENDER_INK_PAGE_TOOL_NAME
    override val description: String = RENDER_INK_PAGE_TOOL_DESCRIPTION
    override val parametersSchema: AssistantToolJsonSchema = RENDER_INK_PAGE_PARAMETERS_SCHEMA
    override val sideEffecting: Boolean = true
    override val executionFailureCode: String = TOOL_ERROR_INK_RENDER_FAILED

    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.session.active &&
            PluginCapability.INK_SURFACE.wireValue in context.session.grantedCapabilities &&
            capabilities.supportsInkSurface()

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return AssistantToolValidation.Invalid()
        val keys = arguments.keys()
        while (keys.hasNext()) {
            if (keys.next() !in RENDER_INK_PAGE_ARGUMENTS) {
                return AssistantToolValidation.Invalid()
            }
        }
        val page = arguments.opt("page")
        if (page !is String || page.isBlank()) return AssistantToolValidation.Invalid()
        if (arguments.has("title") && arguments.opt("title") !is String) {
            return AssistantToolValidation.Invalid()
        }
        if (arguments.has("data") && arguments.opt("data") !is JSONObject) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(arguments)
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult {
        val session = capabilities.currentSession()
            ?: return AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
        if (!capabilities.isSessionActive(session)) {
            return AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
        }

        val result = withTimeoutOrNull(INK_PAGE_SHOW_TIMEOUT_MS) {
            capabilities.showInkPage(
                session = session,
                page = arguments.getString("page"),
                data = arguments.optJSONObject("data"),
            )
        } ?: return AssistantToolResult.Error(TOOL_ERROR_INK_RENDER_FAILED)

        return when (result) {
            RenderInkPageShowResult.Shown -> {
                if (!capabilities.markInkShown(session)) {
                    AssistantToolResult.Error(TOOL_ERROR_CANCELLED)
                } else {
                    AssistantToolResult.Json("{\"status\":\"shown\"}")
                }
            }
            is RenderInkPageShowResult.Rejected -> AssistantToolResult.Error(
                code = TOOL_ERROR_INVALID_INK_PAGE,
                detailsJson = inkProblemsJson(result.problems),
            )
            is RenderInkPageShowResult.Failed -> AssistantToolResult.Error(result.code)
        }
    }

    private companion object {
        const val INK_PAGE_SHOW_TIMEOUT_MS = 8_000L
        val RENDER_INK_PAGE_ARGUMENTS = setOf("page", "title", "data")
    }
}

internal const val RENDER_INK_PAGE_TOOL_NAME = "render_ink_page"
internal const val TOOL_ERROR_INVALID_INK_PAGE = "invalid_ink_page"
internal const val TOOL_ERROR_INK_RENDER_FAILED = "ink_render_failed"
internal const val TOOL_ERROR_INK_SURFACE_UNAVAILABLE = "ink_surface_unavailable"
internal const val TOOL_ERROR_SURFACE_BUSY = "surface_busy"

internal val RENDER_INK_PAGE_PARAMETERS_SCHEMA = AssistantToolJsonSchema(
    """{"type":"object","properties":{"page":{"type":"string","minLength":1},"title":{"type":"string"},"data":{"type":"object"}},"required":["page"],"additionalProperties":false}""",
)

internal val RENDER_INK_PAGE_TOOL_DESCRIPTION = """
    Render a rich Nexus Ink page on the glasses as optional presentation alongside the required normal text answer. Use it for numbers, comparisons, trends, metric layouts, or multi-value/animated status; do not use it for plain prose.

    Nexus Ink v1 supports view, text, asset-only image, scroll-view, progress, chart (line/area/pie/radar/bar), lottie-view, and nx-canvas; wx:if/elif/else, wx:for, interpolation, and bounded expressions. Set display:flex explicitly on every layout container. No <script setup>, JavaScript, URLs, filters, keyframes, or media queries. Page <=32 KiB, data <=16 KiB, total <=64 KiB, <=256 nodes, <=4 chart series x 256 points, <=512 canvas commands, and each Lottie JSON <=32 KiB. Monochrome only: do not author color styling; identify every chart series/point by label.

    Compact example page:
    <script type="application/json" def>{"data":{"title":"Trend","points":[{"label":"Now","value":1}]}}</script>
    <page><view class="page"><text class="title">{{ title }}</text><chart class="chart" type="line" series="value" data="{{ points }}" animate="true" /></view></page>
    <style>.page { display: flex; flex-direction: column; gap: 12rpx; padding: 20rpx; } .title { font-size: 36rpx; font-weight: 700; } .chart { width: 100%; height: 180rpx; }</style>
""".trimIndent()

private fun inkProblemsJson(problems: List<NexusInkProblem>): String =
    JSONObject()
        .put(
            "problems",
            JSONArray().apply {
                problems.forEach { problem ->
                    put(
                        JSONObject()
                            .put("code", problem.code)
                            .put("message", problem.message)
                            .put("severity", problem.severity)
                            .apply {
                                problem.line?.let { put("line", it) }
                                problem.column?.let { put("column", it) }
                                problem.feature?.let { put("feature", it) }
                            },
                    )
                }
            },
        )
        .toString()
