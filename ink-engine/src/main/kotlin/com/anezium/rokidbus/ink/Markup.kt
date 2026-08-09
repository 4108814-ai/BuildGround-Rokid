package com.anezium.rokidbus.ink

internal sealed interface BindingPart {
    data class Literal(val value: String) : BindingPart
    data class Expression(val id: Int, val source: String, val ast: Expr) : BindingPart
}

internal data class BindingValue(val parts: List<BindingPart>) {
    val exactExpression: BindingPart.Expression?
        get() = parts.singleOrNull() as? BindingPart.Expression

    val literalValue: String?
        get() = if (parts.all { it is BindingPart.Literal }) {
            parts.joinToString("") { (it as BindingPart.Literal).value }
        } else {
            null
        }
}

internal sealed interface TemplateNode {
    val location: SourceLocation

    data class Text(
        val value: BindingValue,
        override val location: SourceLocation,
        val structuralIndex: Int,
    ) : TemplateNode

    data class Element(
        val tag: String,
        val attributes: Map<String, BindingValue>,
        val children: List<TemplateNode>,
        override val location: SourceLocation,
        val structuralIndex: Int,
    ) : TemplateNode
}

internal data class MarkupParseResult(
    val roots: List<TemplateNode>,
    val problems: List<InkProblem>,
)

internal class WxmlParser(
    private val source: String,
    private val baseLocation: SourceLocation = SourceLocation(1, 1),
) {
    private val problems = mutableListOf<InkProblem>()
    private val roots = mutableListOf<TemplateNode>()
    private val stack = mutableListOf<ElementBuilder>()
    private var index = 0
    private var expressionId = 0
    private var structuralIndex = 0
    private var failed = false

    fun parse(): MarkupParseResult {
        while (index < source.length && !failed) {
            when {
                source.startsWith("<!--", index) -> parseComment()
                source[index] == '<' -> parseTag()
                else -> parseText()
            }
        }
        if (!failed && stack.isNotEmpty()) {
            val open = stack.last()
            invalid(open.start, "Unclosed <${open.tag}> tag", open.tag)
        }
        if (!failed) validateConditionalChains(roots)
        return MarkupParseResult(roots.toList(), problems.toList())
    }

    private fun parseComment() {
        val end = source.indexOf("-->", index + 4)
        if (end < 0) {
            invalid(index, "Unclosed markup comment", "comment")
        } else {
            index = end + 3
        }
    }

    private fun parseTag() {
        val start = index
        index++
        if (source.getOrNull(index) == '/') {
            index++
            skipWhitespace()
            val name = readName()
            if (name == null) {
                invalid(start, "Invalid closing tag", "closing tag")
                return
            }
            skipWhitespace()
            if (source.getOrNull(index) != '>') {
                invalid(index, "Expected '>' after </$name>", name)
                return
            }
            index++
            val builder = stack.lastOrNull()
            if (builder == null || builder.tag != name) {
                val expected = builder?.tag?.let { "</$it>" } ?: "no closing tag"
                invalid(start, "Bad tag nesting: found </$name>, expected $expected", name)
                return
            }
            stack.removeAt(stack.lastIndex)
            appendNode(builder.build())
            return
        }
        if (source.getOrNull(index) == '!' || source.getOrNull(index) == '?') {
            invalid(start, "Unsupported markup declaration", "declaration")
            return
        }

        val name = readName()
        if (name == null) {
            invalid(start, "Invalid opening tag", "opening tag")
            return
        }
        val attributes = linkedMapOf<String, BindingValue>()
        var selfClosing = false
        while (index < source.length) {
            skipWhitespace()
            when {
                source.startsWith("/>", index) -> {
                    selfClosing = true
                    index += 2
                    break
                }
                source.getOrNull(index) == '>' -> {
                    index++
                    break
                }
                else -> {
                    val attributeStart = index
                    val attributeName = readAttributeName()
                    if (attributeName == null) {
                        invalid(index, "Invalid attribute syntax on <$name>", name)
                        return
                    }
                    if (attributes.containsKey(attributeName)) {
                        invalid(attributeStart, "Duplicate attribute '$attributeName'", attributeName)
                        return
                    }
                    skipWhitespace()
                    val value = if (source.getOrNull(index) == '=') {
                        index++
                        skipWhitespace()
                        val quote = source.getOrNull(index)
                        if (quote != '\'' && quote != '"') {
                            invalid(index, "Attribute '$attributeName' must use a quoted value", attributeName)
                            return
                        }
                        index++
                        val valueStart = index
                        val end = source.indexOf(quote, index)
                        if (end < 0) {
                            invalid(attributeStart, "Unclosed value for '$attributeName'", attributeName)
                            return
                        }
                        index = end + 1
                        parseBinding(source.substring(valueStart, end), valueStart)
                    } else {
                        BindingValue(listOf(BindingPart.Literal("true")))
                    }
                    attributes[attributeName] = value
                }
            }
        }
        if (index > source.length || (index == source.length && source.lastOrNull() != '>')) {
            invalid(start, "Unclosed <$name> tag", name)
            return
        }

        val location = location(start)
        validateElement(name, attributes, location)
        val builder = ElementBuilder(name, attributes.toMap(), location, start, structuralIndex++)
        if (selfClosing) appendNode(builder.build()) else stack += builder
    }

    private fun parseText() {
        val start = index
        val end = source.indexOf('<', start).takeIf { it >= 0 } ?: source.length
        index = end
        val raw = decodeEntities(source.substring(start, end))
        if (raw.isBlank()) return
        val value = parseBinding(raw, start)
        appendNode(TemplateNode.Text(value, location(start), structuralIndex++))
    }

    private fun parseBinding(raw: String, sourceOffset: Int): BindingValue {
        val parts = mutableListOf<BindingPart>()
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf("{{", cursor)
            if (open < 0) {
                if (cursor < raw.length) parts += BindingPart.Literal(raw.substring(cursor))
                break
            }
            if (open > cursor) parts += BindingPart.Literal(raw.substring(cursor, open))
            val close = raw.indexOf("}}", open + 2)
            if (close < 0) {
                expressionProblem(sourceOffset + open, InkProblemCodes.EXPR_INVALID, "Unclosed interpolation", raw.substring(open))
                parts += BindingPart.Literal(raw.substring(open))
                break
            }
            val expressionSource = raw.substring(open + 2, close).trim()
            if (expressionSource.isEmpty()) {
                expressionProblem(sourceOffset + open, InkProblemCodes.EXPR_INVALID, "Empty interpolation", expressionSource)
            } else {
                try {
                    parts += BindingPart.Expression(expressionId++, expressionSource, ExpressionParser(expressionSource).parse())
                } catch (error: ExpressionException) {
                    expressionProblem(sourceOffset + open + 2 + error.offset, error.code, error.message, expressionSource)
                }
            }
            cursor = close + 2
        }
        if (parts.isEmpty()) parts += BindingPart.Literal("")
        return BindingValue(parts)
    }

    private fun validateElement(
        tag: String,
        attributes: Map<String, BindingValue>,
        location: SourceLocation,
    ) {
        if (tag !in SUPPORTED_COMPONENTS) {
            problems += InkProblem(
                InkProblemCodes.COMPONENT_UNSUPPORTED,
                "Component <$tag> is not supported by Ink Surface v1",
                line = location.line,
                column = location.column,
                feature = tag,
            )
        }
        attributes.forEach { (name, value) ->
            val allowed = name in COMMON_ATTRIBUTES ||
                name.startsWith("data-") && name.length > 5 ||
                (name.startsWith("bind") || name.startsWith("catch")) && name.length > 4 ||
                normalizeDirective(name) != null ||
                name in InkComponentContract.attributesFor(tag)
            if (!allowed) {
                problems += InkProblem(
                    InkProblemCodes.ATTRIBUTE_UNSUPPORTED,
                    "Attribute '$name' is not supported on <$tag>",
                    line = location.line,
                    column = location.column,
                    feature = name,
                )
            }
            if ((name.startsWith("bind") || name.startsWith("catch")) && value.literalValue.isNullOrBlank()) {
                problems += InkProblem(
                    InkProblemCodes.MARKUP_INVALID,
                    "Event '$name' requires a literal action id",
                    line = location.line,
                    column = location.column,
                    feature = name,
                )
            }
        }
        val directives = attributes.keys.mapNotNull(::normalizeDirective)
        if (directives.count { it in CONDITIONAL_DIRECTIVES } > 1) {
            problems += InkProblem(
                InkProblemCodes.MARKUP_INVALID,
                "An element may only have one conditional directive",
                line = location.line,
                column = location.column,
                feature = directives.joinToString(),
            )
        }
        val hasFor = "for" in directives
        if (!hasFor && directives.any { it in setOf("for-item", "for-index", "key") }) {
            problems += InkProblem(
                InkProblemCodes.MARKUP_INVALID,
                "for-item, for-index, and key require a for directive",
                line = location.line,
                column = location.column,
                feature = "for",
            )
        }
    }

    private fun validateConditionalChains(nodes: List<TemplateNode>) {
        var inChain = false
        var sawElse = false
        nodes.forEach { node ->
            if (node !is TemplateNode.Element) {
                inChain = false
                sawElse = false
                return@forEach
            }
            val directives = node.attributes.keys.mapNotNull(::normalizeDirective)
            val directive = directives.firstOrNull { it in CONDITIONAL_DIRECTIVES }
            // The upstream docs never define how a condition and a loop combine on one
            // element, so accepting both would pick semantics for them silently.
            if (directive != null && "for" in directives) {
                problems += InkProblem(
                    InkProblemCodes.MARKUP_INVALID,
                    "Combining $directive with for on one element is not supported; nest the condition inside the loop element",
                    line = node.location.line,
                    column = node.location.column,
                    feature = "$directive+for",
                )
            }
            when (directive) {
                "if" -> {
                    inChain = true
                    sawElse = false
                }
                "elif" -> {
                    if (!inChain || sawElse) conditionalChainError(node, "elif")
                }
                "else" -> {
                    if (!inChain || sawElse) conditionalChainError(node, "else")
                    sawElse = true
                }
                else -> {
                    inChain = false
                    sawElse = false
                }
            }
            validateConditionalChains(node.children)
        }
    }

    private fun conditionalChainError(node: TemplateNode.Element, directive: String) {
        problems += InkProblem(
            InkProblemCodes.MARKUP_INVALID,
            "$directive must immediately follow an if/elif sibling",
            line = node.location.line,
            column = node.location.column,
            feature = directive,
        )
    }

    private fun appendNode(node: TemplateNode) {
        if (stack.isEmpty()) roots += node else stack.last().children += node
    }

    private fun readName(): String? {
        val start = index
        while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it == '-' || it == '_' } == true) index++
        return source.substring(start, index).takeIf { it.isNotEmpty() }
    }

    private fun readAttributeName(): String? {
        val start = index
        while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it in "-_:" } == true) index++
        return source.substring(start, index).takeIf { it.isNotEmpty() }
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index++
    }

    private fun invalid(offset: Int, message: String, feature: String) {
        val location = location(offset)
        problems += InkProblem(
            InkProblemCodes.MARKUP_INVALID,
            message,
            line = location.line,
            column = location.column,
            feature = feature,
        )
        failed = true
    }

    private fun expressionProblem(offset: Int, code: String, message: String, feature: String) {
        val location = location(offset)
        problems += InkProblem(code, message, line = location.line, column = location.column, feature = feature)
    }

    private fun location(offset: Int): SourceLocation {
        val local = lineColumn(source, offset)
        return SourceLocation(
            line = baseLocation.line + local.line - 1,
            column = if (local.line == 1) baseLocation.column + local.column - 1 else local.column,
        )
    }

    private data class ElementBuilder(
        val tag: String,
        val attributes: Map<String, BindingValue>,
        val location: SourceLocation,
        val start: Int,
        val structuralIndex: Int,
        val children: MutableList<TemplateNode> = mutableListOf(),
    ) {
        fun build(): TemplateNode.Element = TemplateNode.Element(
            tag,
            attributes,
            children.toList(),
            location,
            structuralIndex,
        )
    }

    companion object {
        private val SUPPORTED_COMPONENTS = InkComponentContract.supportedComponents
        private val COMMON_ATTRIBUTES = setOf("id", "class", "style")
        private val CONDITIONAL_DIRECTIVES = setOf("if", "elif", "else")

        fun normalizeDirective(attribute: String): String? = when {
            attribute.startsWith("wx:") -> attribute.removePrefix("wx:")
            attribute.startsWith("ink:") -> attribute.removePrefix("ink:")
            else -> null
        }.takeIf { it in setOf("if", "elif", "else", "for", "for-item", "for-index", "key") }

        fun findDirective(attributes: Map<String, BindingValue>, name: String): BindingValue? =
            attributes.entries.firstOrNull { normalizeDirective(it.key) == name }?.value
    }
}

private fun decodeEntities(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
