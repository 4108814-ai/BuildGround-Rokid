package com.anezium.rokidbus.ink

import org.json.JSONObject

sealed interface InkSource {
    data class Sfc(val text: String) : InkSource

    data class MultiFile(
        val wxml: String,
        val wxss: String = "",
        val definition: JSONObject? = null,
    ) : InkSource
}

internal data class SourceLocation(val line: Int, val column: Int)

internal data class InkBlocks(
    val page: String?,
    val style: String,
    val definition: InkObject,
    val metadata: InkObject,
    val problems: List<InkProblem>,
    val pageLocation: SourceLocation = SourceLocation(1, 1),
    val styleLocation: SourceLocation = SourceLocation(1, 1),
)

internal object SfcSplitter {
    fun split(source: InkSource): InkBlocks = when (source) {
        is InkSource.MultiFile -> splitMultiFile(source)
        is InkSource.Sfc -> splitSfc(source.text)
    }

    private fun splitMultiFile(source: InkSource.MultiFile): InkBlocks {
        val definition = source.definition?.toInkObject() ?: linkedMapOf()
        return fromParts(source.wxml, source.wxss, definition, emptyList())
    }

    private fun splitSfc(text: String): InkBlocks {
        val problems = mutableListOf<InkProblem>()
        var page: String? = null
        var style = ""
        var definition: InkObject? = null
        var pageLocation = SourceLocation(1, 1)
        var styleLocation = SourceLocation(1, 1)
        var index = 0

        while (true) {
            index = skipTrivia(text, index, problems)
            if (index >= text.length) break
            if (text[index] != '<') {
                problems += problem(text, index, InkProblemCodes.BLOCK_UNKNOWN, "Content outside an Ink block", "text")
                index = text.indexOf('<', index).takeIf { it >= 0 } ?: text.length
                continue
            }

            val opening = readOpening(text, index)
            if (opening == null) {
                problems += problem(text, index, InkProblemCodes.BLOCK_UNKNOWN, "Invalid top-level Ink block", "markup")
                break
            }
            val close = Regex("</\\s*${Regex.escape(opening.name)}\\s*>", RegexOption.IGNORE_CASE)
                .find(text, opening.end)
            if (close == null) {
                problems += problem(
                    text,
                    index,
                    InkProblemCodes.MARKUP_INVALID,
                    "Unclosed top-level <${opening.name}> block",
                    opening.name,
                )
                break
            }
            val content = text.substring(opening.end, close.range.first)
            val contentLocation = lineColumn(text, opening.end)
            when (opening.name.lowercase()) {
                "page" -> {
                    if (page != null) {
                        problems += problem(text, index, InkProblemCodes.BLOCK_UNKNOWN, "Duplicate <page> block", "page")
                    } else {
                        page = content
                        pageLocation = contentLocation
                    }
                }
                "style" -> {
                    if (style.isNotEmpty()) {
                        problems += problem(text, index, InkProblemCodes.BLOCK_UNKNOWN, "Duplicate <style> block", "style")
                    } else {
                        style = content
                        styleLocation = contentLocation
                    }
                }
                "script" -> when {
                    "setup" in opening.attributes -> problems += problem(
                        text,
                        index,
                        InkProblemCodes.SCRIPT_UNSUPPORTED,
                        "<script setup> is not supported by Ink Surface v1",
                        "script setup",
                    )
                    "def" in opening.attributes -> {
                        if (definition != null) {
                            problems += problem(text, index, InkProblemCodes.BLOCK_UNKNOWN, "Duplicate <script def> block", "script def")
                        } else {
                            definition = parseDefinition(content, text, opening.end, problems)
                        }
                    }
                    else -> problems += problem(
                        text,
                        index,
                        InkProblemCodes.BLOCK_UNKNOWN,
                        "Unknown top-level <script> block",
                        "script",
                    )
                }
                else -> problems += problem(
                    text,
                    index,
                    InkProblemCodes.BLOCK_UNKNOWN,
                    "Unknown top-level <${opening.name}> block",
                    opening.name,
                )
            }
            index = close.range.last + 1
        }

        if (definition == null) {
            problems += InkProblem(
                InkProblemCodes.BLOCK_REQUIRED,
                "A single-file Ink page requires a <script def> block",
                feature = "script def",
            )
        }
        if (page == null) {
            problems += InkProblem(
                InkProblemCodes.BLOCK_REQUIRED,
                "An Ink page requires a <page> block",
                feature = "page",
            )
        }
        val result = fromParts(page, style, definition ?: linkedMapOf(), problems)
        return result.copy(pageLocation = pageLocation, styleLocation = styleLocation)
    }

    private fun fromParts(
        page: String?,
        style: String,
        definition: InkObject,
        problems: List<InkProblem>,
    ): InkBlocks {
        val collectedProblems = problems.toMutableList()
        if (definition.containsKey("data") && definition["data"] !is Map<*, *>) {
            collectedProblems += InkProblem(
                InkProblemCodes.DEFINITION_INVALID,
                "The script-def data member must be an object",
                feature = "data",
            )
        }
        val data = definition["data"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val initial = LinkedHashMap<String, Any?>().also { target ->
            data.entries.sortedBy { it.key.toString() }.forEach { (key, value) -> target[key.toString()] = value.deepCopyInk() }
        }
        val metadata = LinkedHashMap<String, Any?>().also { target ->
            definition.filterKeys { it != "data" }.forEach { (key, value) -> target[key] = value.deepCopyInk() }
        }
        return InkBlocks(page, style, initial, metadata, collectedProblems)
    }

    private fun parseDefinition(
        content: String,
        source: String,
        offset: Int,
        problems: MutableList<InkProblem>,
    ): InkObject = try {
        JSONObject(content).toInkObject()
    } catch (error: Exception) {
        val location = lineColumn(source, offset)
        problems += InkProblem(
            InkProblemCodes.DEFINITION_INVALID,
            "Invalid JSON in <script def>: ${error.message ?: "parse failure"}",
            line = location.line,
            column = location.column,
            feature = "script def",
        )
        linkedMapOf()
    }

    private data class Opening(val name: String, val attributes: Set<String>, val end: Int)

    private fun readOpening(text: String, start: Int): Opening? {
        var index = start + 1
        val nameStart = index
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '-')) index++
        if (index == nameStart) return null
        val name = text.substring(nameStart, index)
        var quote: Char? = null
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char == '>') {
                val rawAttributes = text.substring(nameStart + name.length, index)
                val attributes = Regex("(?:^|\\s)([A-Za-z][A-Za-z0-9_-]*)(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?")
                    .findAll(rawAttributes)
                    .map { it.groupValues[1].lowercase() }
                    .toSet()
                return Opening(name, attributes, index + 1)
            }
            index++
        }
        return null
    }

    private fun skipTrivia(text: String, initial: Int, problems: MutableList<InkProblem>): Int {
        var index = initial
        while (index < text.length) {
            while (index < text.length && text[index].isWhitespace()) index++
            if (!text.startsWith("<!--", index)) break
            val end = text.indexOf("-->", index + 4)
            if (end < 0) {
                problems += problem(text, index, InkProblemCodes.MARKUP_INVALID, "Unclosed top-level comment", "comment")
                return text.length
            }
            index = end + 3
        }
        return index
    }

    private fun problem(source: String, index: Int, code: String, message: String, feature: String): InkProblem {
        val location = lineColumn(source, index)
        return InkProblem(code, message, line = location.line, column = location.column, feature = feature)
    }
}

internal fun lineColumn(source: String, index: Int): SourceLocation {
    var line = 1
    var column = 1
    for (position in 0 until index.coerceAtMost(source.length)) {
        if (source[position] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }
    return SourceLocation(line, column)
}
