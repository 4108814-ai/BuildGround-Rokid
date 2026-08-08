package com.anezium.rokidbus.ink

enum class InkStyleValueKind {
    NUMBER,
    COLOR,
    VARIABLE,
    TRANSFORM,
    TRANSITION,
    KEYWORD,
    SEQUENCE,
}

sealed interface InkStyleToken {
    data class Dimension(val value: Double, val unit: String?) : InkStyleToken
    data class Color(val value: String) : InkStyleToken
    data class Identifier(val value: String) : InkStyleToken
    data class Function(val name: String, val arguments: List<InkStyleToken>) : InkStyleToken
    data class StringLiteral(val value: String) : InkStyleToken
    data class Delimiter(val value: String) : InkStyleToken
}

data class InkStyleValue(
    val cssText: String,
    val kind: InkStyleValueKind,
    val tokens: List<InkStyleToken>,
)

data class InkStyleRule(
    val className: String,
    val declarations: Map<String, InkStyleValue>,
    val sourceOrder: Int,
)

data class InkStyleParseResult(
    val rules: List<InkStyleRule>,
    val problems: List<InkProblem>,
)

object InkStyles {
    fun parse(wxss: String): InkStyleParseResult = WxssParser(wxss).parse()
}

internal class WxssParser(
    private val originalSource: String,
    private val baseLocation: SourceLocation = SourceLocation(1, 1),
) {
    private val source = stripComments(originalSource)
    private val problems = mutableListOf<InkProblem>()
    private val rules = mutableListOf<InkStyleRule>()
    private var index = 0

    fun parse(): InkStyleParseResult {
        while (index < source.length) {
            skipWhitespace()
            if (index >= source.length) break
            if (source[index] == '@') {
                parseAtRule()
                continue
            }
            parseRule()
        }
        return InkStyleParseResult(rules.toList(), problems.toList())
    }

    fun parseInline(declarations: String): Pair<Map<String, InkStyleValue>, List<InkProblem>> {
        val parsed = parseDeclarations(declarations, 0)
        return parsed to problems.toList()
    }

    private fun parseRule() {
        val selectorStart = index
        val open = source.indexOf('{', index)
        if (open < 0) {
            warning(InkProblemCodes.STYLE_UNSUPPORTED, selectorStart, "Unclosed style rule", source.substring(selectorStart).trim())
            index = source.length
            return
        }
        val selector = source.substring(selectorStart, open).trim()
        val close = findMatchingBrace(open)
        if (close < 0) {
            warning(InkProblemCodes.STYLE_UNSUPPORTED, open, "Unclosed declaration block", selector)
            index = source.length
            return
        }
        index = close + 1
        if (!CLASS_SELECTOR.matches(selector)) {
            error(
                InkProblemCodes.SELECTOR_UNSUPPORTED,
                selectorStart,
                "Only a single class selector is supported by Ink Surface v1: '$selector'",
                selector,
            )
            return
        }
        val declarations = parseDeclarations(source.substring(open + 1, close), open + 1)
        rules += InkStyleRule(selector.substring(1), declarations, rules.size)
    }

    private fun parseAtRule() {
        val start = index
        val nameEnd = (index + 1 until source.length)
            .firstOrNull { !source[it].isLetter() && source[it] != '-' }
            ?: source.length
        val name = source.substring(index + 1, nameEnd).lowercase()
        when {
            name == "keyframes" || name.endsWith("keyframes") -> {
                val open = source.indexOf('{', nameEnd)
                val close = if (open >= 0) findMatchingBrace(open) else -1
                warning(
                    InkProblemCodes.STYLE_EXCLUDED,
                    start,
                    "Keyframe animations are explicitly excluded from Ink Surface v1",
                    "@$name",
                )
                index = if (close >= 0) close + 1 else source.length
            }
            name == "import" -> {
                val end = source.indexOf(';', nameEnd)
                warning(
                    InkProblemCodes.STYLE_UNSUPPORTED,
                    start,
                    "WXSS imports must be resolved before compilation",
                    "@import",
                )
                index = if (end >= 0) end + 1 else source.length
            }
            else -> {
                val open = source.indexOf('{', nameEnd)
                val semicolon = source.indexOf(';', nameEnd)
                val close = if (open >= 0 && (semicolon < 0 || open < semicolon)) findMatchingBrace(open) else -1
                error(
                    InkProblemCodes.SELECTOR_UNSUPPORTED,
                    start,
                    "At-rule '@$name' is not supported by Ink Surface v1",
                    "@$name",
                )
                index = when {
                    close >= 0 -> close + 1
                    semicolon >= 0 -> semicolon + 1
                    else -> source.length
                }
            }
        }
    }

    private fun parseDeclarations(block: String, sourceOffset: Int): Map<String, InkStyleValue> {
        val declarations = linkedMapOf<String, InkStyleValue>()
        splitTopLevel(block, ';').forEach { part ->
            val raw = part.value.trim()
            if (raw.isEmpty()) return@forEach
            val colon = findTopLevel(raw, ':')
            val offset = sourceOffset + part.offset + part.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (colon <= 0) {
                warning(InkProblemCodes.STYLE_UNSUPPORTED, offset, "Invalid style declaration '$raw'", raw)
                return@forEach
            }
            val property = raw.substring(0, colon).trim().lowercase()
            val valueText = normalizeCss(raw.substring(colon + 1))
            if (!PROPERTY_NAME.matches(property) || valueText.isEmpty()) {
                warning(InkProblemCodes.STYLE_UNSUPPORTED, offset, "Invalid style declaration '$raw'", property)
                return@forEach
            }
            if (property == "animation" || property.startsWith("animation-")) {
                warning(
                    InkProblemCodes.STYLE_EXCLUDED,
                    offset,
                    "Animation property '$property' is explicitly excluded from Ink Surface v1",
                    property,
                )
                return@forEach
            }
            if (property in EXPLICITLY_EXCLUDED_PROPERTIES || property == "position" && valueText == "sticky") {
                warning(
                    InkProblemCodes.STYLE_EXCLUDED,
                    offset,
                    "Style feature '$property: $valueText' is explicitly excluded from Ink Surface v1",
                    property,
                )
                return@forEach
            }
            if (!property.startsWith("--") && property !in ALLOWED_PROPERTIES) {
                warning(
                    InkProblemCodes.STYLE_UNSUPPORTED,
                    offset,
                    "Style property '$property' is not supported by Ink Surface v1",
                    property,
                )
                return@forEach
            }
            if (!validValue(property, valueText)) {
                warning(
                    InkProblemCodes.STYLE_UNSUPPORTED,
                    offset,
                    "Value '$valueText' is not supported for '$property'",
                    property,
                )
                return@forEach
            }
            val value = parseValue(property, valueText)
            declarations[property] = value
            if (containsLiteralColor(valueText)) {
                warning(
                    InkProblemCodes.COLOR_LITERAL,
                    offset,
                    "Literal colors are accepted but should use the monochrome-green design tokens",
                    property,
                )
            }
        }
        return declarations.toMap()
    }

    private fun validValue(property: String, value: String): Boolean {
        if (!balanced(value)) return false
        if (UNKNOWN_UNIT.containsMatchIn(value)) return false
        if (property == "display" && value != "flex") return false
        if (property == "position" && value !in setOf("relative", "absolute")) return false
        if (property == "transform") {
            val remainder = TRANSFORM_FUNCTION.replace(value, "").trim()
            if (remainder.isNotEmpty() || !TRANSFORM_FUNCTION.containsMatchIn(value)) return false
            val names = TRANSFORM_FUNCTION.findAll(value).map { it.groupValues[1].lowercase() }
            if (names.any { it !in TRANSFORM_NAMES }) return false
        }
        if (property == "color" || property == "background-color" || property.endsWith("-color")) {
            if (!isColor(value) && !value.startsWith("var(")) return false
        }
        if (property == "opacity") {
            val number = value.toDoubleOrNull() ?: return value.startsWith("var(")
            if (number !in 0.0..1.0) return false
        }
        return true
    }

    private fun parseValue(property: String, value: String): InkStyleValue {
        val kind = when {
            value.startsWith("var(") -> InkStyleValueKind.VARIABLE
            isColor(value) -> InkStyleValueKind.COLOR
            property == "transform" -> InkStyleValueKind.TRANSFORM
            property.startsWith("transition") -> InkStyleValueKind.TRANSITION
            NUMBER_VALUE.matches(value) -> InkStyleValueKind.NUMBER
            value.any(Char::isWhitespace) -> InkStyleValueKind.SEQUENCE
            else -> InkStyleValueKind.KEYWORD
        }
        return InkStyleValue(value, kind, parseStyleTokens(value))
    }

    private fun isColor(value: String): Boolean = HEX_COLOR.matches(value) || RGB_COLOR.matches(value)

    private fun containsLiteralColor(value: String): Boolean =
        HEX_COLOR_FIND.containsMatchIn(value) || RGB_COLOR_FIND.containsMatchIn(value)

    private fun findMatchingBrace(open: Int): Int {
        var depth = 0
        var quote: Char? = null
        var cursor = open
        while (cursor < source.length) {
            val char = source[cursor]
            if (quote != null) {
                if (char == quote && source.getOrNull(cursor - 1) != '\\') quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cursor
                }
            }
            cursor++
        }
        return -1
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun warning(code: String, offset: Int, message: String, feature: String) {
        problem(code, InkProblemSeverity.WARNING, offset, message, feature)
    }

    private fun error(code: String, offset: Int, message: String, feature: String) {
        problem(code, InkProblemSeverity.ERROR, offset, message, feature)
    }

    private fun problem(code: String, severity: InkProblemSeverity, offset: Int, message: String, feature: String) {
        val location = location(offset)
        problems += InkProblem(code, message, severity, location.line, location.column, feature)
    }

    private fun location(offset: Int): SourceLocation {
        val local = lineColumn(source, offset)
        return SourceLocation(
            baseLocation.line + local.line - 1,
            if (local.line == 1) baseLocation.column + local.column - 1 else local.column,
        )
    }

    private companion object {
        val CLASS_SELECTOR = Regex("\\.[A-Za-z_][A-Za-z0-9_-]*")
        val PROPERTY_NAME = Regex("(?:--)?[A-Za-z][A-Za-z0-9-]*")
        val NUMBER_VALUE = Regex("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:px|rpx|%|ms|s|deg)?", RegexOption.IGNORE_CASE)
        val HEX_COLOR = Regex("#[0-9a-fA-F]{3,4}(?:[0-9a-fA-F]{3,4})?")
        val RGB_COLOR = Regex("rgba?\\(\\s*[-+]?\\d+(?:\\.\\d+)?%?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?%?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?%?(?:\\s*,\\s*[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)%?)?\\s*\\)", RegexOption.IGNORE_CASE)
        val HEX_COLOR_FIND = Regex("#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])")
        val RGB_COLOR_FIND = Regex("rgba?\\([^)]*\\)", RegexOption.IGNORE_CASE)
        val UNKNOWN_UNIT = Regex("(?<![A-Za-z0-9_-])[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(vh|vw|em|rem|vmin|vmax|cm|mm|in|pt|pc)(?![A-Za-z])", RegexOption.IGNORE_CASE)
        val TRANSFORM_FUNCTION = Regex("([A-Za-z]+)\\([^()]*\\)")
        val TRANSFORM_NAMES = setOf("translate", "translatex", "translatey", "scale", "scalex", "scaley", "rotate")
        val EXPLICITLY_EXCLUDED_PROPERTIES = setOf("font-variant", "word-break", "visibility")
        val ALLOWED_PROPERTIES = buildSet {
            addAll(
                setOf(
                    "display",
                    "flex-direction",
                    "flex-wrap",
                    "justify-content",
                    "align-items",
                    "align-self",
                    "flex",
                    "flex-grow",
                    "flex-shrink",
                    "flex-basis",
                    "gap",
                    "width",
                    "height",
                    "min-width",
                    "min-height",
                    "max-width",
                    "max-height",
                    "margin",
                    "padding",
                    "box-sizing",
                    "border",
                    "border-width",
                    "border-color",
                    "border-style",
                    "border-radius",
                    "font-size",
                    "font-weight",
                    "line-height",
                    "text-align",
                    "text-overflow",
                    "white-space",
                    "opacity",
                    "color",
                    "background-color",
                    "transform",
                    "transition",
                    "transition-property",
                    "transition-duration",
                    "transition-timing-function",
                    "transition-delay",
                    "position",
                    "overflow",
                    "top",
                    "right",
                    "bottom",
                    "left",
                    "inset",
                ),
            )
            listOf("margin", "padding").forEach { prefix ->
                listOf("top", "right", "bottom", "left").forEach { side -> add("$prefix-$side") }
            }
            listOf("top", "right", "bottom", "left").forEach { side ->
                add("border-$side")
                add("border-$side-width")
                add("border-$side-color")
                add("border-$side-style")
            }
            listOf("top-left", "top-right", "bottom-right", "bottom-left").forEach { corner ->
                add("border-$corner-radius")
            }
        }
    }
}

private data class CssPart(val value: String, val offset: Int)

private fun splitTopLevel(value: String, delimiter: Char): List<CssPart> {
    val result = mutableListOf<CssPart>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    value.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && value.getOrNull(index - 1) != '\\') quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '(' -> depth++
            ')' -> depth--
            delimiter -> if (depth == 0) {
                result += CssPart(value.substring(start, index), start)
                start = index + 1
            }
        }
    }
    result += CssPart(value.substring(start), start)
    return result
}

private fun findTopLevel(value: String, target: Char): Int {
    var depth = 0
    var quote: Char? = null
    value.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && value.getOrNull(index - 1) != '\\') quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '(' -> depth++
            ')' -> depth--
            target -> if (depth == 0) return index
        }
    }
    return -1
}

private fun balanced(value: String): Boolean {
    var depth = 0
    var quote: Char? = null
    value.forEachIndexed { index, char ->
        if (quote != null) {
            if (char == quote && value.getOrNull(index - 1) != '\\') quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '(' -> depth++
            ')' -> if (--depth < 0) return false
        }
    }
    return depth == 0 && quote == null
}

private fun normalizeCss(value: String): String = value.trim().replace(Regex("\\s+"), " ")

private fun stripComments(value: String): String {
    val result = StringBuilder(value)
    var index = 0
    while (index < result.length - 1) {
        if (result[index] == '/' && result[index + 1] == '*') {
            val end = result.indexOf("*/", index + 2)
            val stop = if (end >= 0) end + 2 else result.length
            for (position in index until stop) if (result[position] != '\n') result.setCharAt(position, ' ')
            index = stop
        } else {
            index++
        }
    }
    return result.toString()
}

private fun parseStyleTokens(value: String): List<InkStyleToken> {
    val tokens = mutableListOf<InkStyleToken>()
    var index = 0
    while (index < value.length) {
        if (value[index].isWhitespace()) {
            index++
            continue
        }
        val color = Regex("#[0-9a-fA-F]{3,8}(?![0-9a-fA-F])").find(value, index)
            ?.takeIf { it.range.first == index }
        if (color != null) {
            tokens += InkStyleToken.Color(color.value)
            index = color.range.last + 1
            continue
        }
        val rgb = Regex("rgba?\\([^)]*\\)", RegexOption.IGNORE_CASE).find(value, index)
            ?.takeIf { it.range.first == index }
        if (rgb != null) {
            tokens += InkStyleToken.Color(rgb.value)
            index = rgb.range.last + 1
            continue
        }
        val number = Regex("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:px|rpx|%|ms|s|deg)?", RegexOption.IGNORE_CASE)
            .find(value, index)
            ?.takeIf { it.range.first == index }
        if (number != null) {
            val split = Regex("^([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))(.*)$").matchEntire(number.value)!!
            tokens += InkStyleToken.Dimension(split.groupValues[1].toDouble(), split.groupValues[2].ifEmpty { null })
            index = number.range.last + 1
            continue
        }
        if (value[index] == '\'' || value[index] == '"') {
            val quote = value[index]
            val start = ++index
            while (index < value.length && (value[index] != quote || value.getOrNull(index - 1) == '\\')) index++
            tokens += InkStyleToken.StringLiteral(value.substring(start, index.coerceAtMost(value.length)))
            if (index < value.length) index++
            continue
        }
        val identifier = Regex("--?[A-Za-z_][A-Za-z0-9_-]*|[A-Za-z_][A-Za-z0-9_-]*")
            .find(value, index)
            ?.takeIf { it.range.first == index }
        if (identifier != null) {
            index = identifier.range.last + 1
            if (value.getOrNull(index) == '(') {
                val close = findFunctionClose(value, index)
                if (close > index) {
                    tokens += InkStyleToken.Function(
                        identifier.value,
                        parseStyleTokens(value.substring(index + 1, close)),
                    )
                    index = close + 1
                    continue
                }
            }
            tokens += InkStyleToken.Identifier(identifier.value)
            continue
        }
        tokens += InkStyleToken.Delimiter(value[index].toString())
        index++
    }
    return tokens
}

private fun findFunctionClose(value: String, open: Int): Int {
    var depth = 0
    var quote: Char? = null
    for (index in open until value.length) {
        val char = value[index]
        if (quote != null) {
            if (char == quote && value.getOrNull(index - 1) != '\\') quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '(' -> depth++
            ')' -> if (--depth == 0) return index
        }
    }
    return -1
}
