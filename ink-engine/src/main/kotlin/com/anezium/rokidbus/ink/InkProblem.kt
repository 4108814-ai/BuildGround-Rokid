package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject

enum class InkProblemSeverity {
    ERROR,
    WARNING,
}

data class InkProblem(
    val code: String,
    val message: String,
    val severity: InkProblemSeverity = InkProblemSeverity.ERROR,
    val line: Int? = null,
    val column: Int? = null,
    val feature: String? = null,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        put("severity", severity.name.lowercase())
        line?.let { put("line", it) }
        column?.let { put("col", it) }
        feature?.let { put("feature", it) }
    }
}

object InkProblemCodes {
    const val BLOCK_REQUIRED = "INK_BLOCK_REQUIRED"
    const val BLOCK_UNKNOWN = "INK_BLOCK_UNKNOWN"
    const val SCRIPT_UNSUPPORTED = "INK_SCRIPT_UNSUPPORTED"
    const val DEFINITION_INVALID = "INK_DEFINITION_INVALID"
    const val COMPONENT_UNSUPPORTED = "INK_COMPONENT_UNSUPPORTED"
    const val COMPONENT_VALUE = "INK_COMPONENT_VALUE"
    const val COMPONENT_BUDGET = "INK_COMPONENT_BUDGET"
    const val COMPONENT_SAMPLE_DERIVED = "INK_COMPONENT_SAMPLE_DERIVED"
    const val ATTRIBUTE_UNSUPPORTED = "INK_ATTRIBUTE_UNSUPPORTED"
    const val ATTRIBUTE_REQUIRED = "INK_ATTRIBUTE_REQUIRED"
    const val ATTRIBUTE_TYPE = "INK_ATTRIBUTE_TYPE"
    const val ATTRIBUTE_VALUE = "INK_ATTRIBUTE_VALUE"
    const val ATTRIBUTE_SOURCE = "INK_ATTRIBUTE_SOURCE"
    const val MARKUP_INVALID = "INK_MARKUP_INVALID"
    const val SELECTOR_UNSUPPORTED = "INK_SELECTOR_UNSUPPORTED"
    const val STYLE_UNSUPPORTED = "INK_STYLE_UNSUPPORTED"
    const val STYLE_EXCLUDED = "INK_STYLE_EXCLUDED"
    const val COLOR_LITERAL = "INK_COLOR_LITERAL"
    const val EXPR_INVALID = "INK_EXPR_INVALID"
    const val EXPR_LIMIT = "INK_EXPR_LIMIT"
    const val BUDGET_NODES = "INK_BUDGET_NODES"
    const val BUDGET_DEPTH = "INK_BUDGET_DEPTH"
    const val BUDGET_SIZE = "INK_BUDGET_SIZE"
    const val THREAD_INVALID = "INK_THREAD_INVALID"
    const val SESSION_NOT_FOUND = "INK_SESSION_NOT_FOUND"
    const val WIRE_INVALID = "INK_WIRE_INVALID"
    const val WIRE_UNKNOWN_FIELD = "INK_WIRE_UNKNOWN_FIELD"
    const val WIRE_TYPE = "INK_WIRE_TYPE"
    const val WIRE_VERSION = "INK_WIRE_VERSION"
    const val WIRE_REVISION = "INK_WIRE_REVISION"
    const val WIRE_ID = "INK_WIRE_ID"
    const val WIRE_ACTION = "INK_WIRE_ACTION"
    const val WIRE_DATASET = "INK_WIRE_DATASET"
}

internal fun List<InkProblem>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { array.put(it.toJsonObject()) }
}

internal class InkFailure(val problem: InkProblem) : RuntimeException(problem.message)
