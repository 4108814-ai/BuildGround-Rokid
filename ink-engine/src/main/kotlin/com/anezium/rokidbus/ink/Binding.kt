package com.anezium.rokidbus.ink

import java.nio.charset.StandardCharsets

internal data class CachedExpression(val value: Any?, val dependencies: Set<DataPath>)

internal data class EvaluationCache(
    val expressions: MutableMap<String, CachedExpression> = mutableMapOf(),
    val reportedStyleProblems: MutableSet<String> = mutableSetOf(),
) {
    fun copyForUpdate(): EvaluationCache = EvaluationCache(expressions.toMutableMap(), reportedStyleProblems.toMutableSet())
}

internal data class BindingResult(
    val document: RenderDocument?,
    val problems: List<InkProblem>,
    val evaluatedExpressionCount: Int,
)

internal class BindingRenderer(
    private val roots: List<TemplateNode>,
    private val rules: List<InkStyleRule>,
    private val metadata: InkObject,
    private val data: InkObject,
    private val cache: EvaluationCache,
    private val documentId: String,
    private val revision: Int,
    private val dirtyPaths: List<DataPath> = emptyList(),
) {
    private val evaluator = ExpressionEvaluator(data)
    private val problems = mutableListOf<InkProblem>()
    private var evaluatedExpressionCount = 0
    private var nodeCount = 0

    fun render(): BindingResult = try {
        val context = EvaluationContext(data)
        val renderedRoots = renderSiblings(roots, context, "root", "root", StyleContext())
        val document = RenderDocument(
            roots = renderedRoots,
            metadata = metadata.deepCopyObject(),
            documentId = documentId,
            revision = revision,
        )
        BindingResult(
            document.takeIf { problems.none { problem -> problem.severity == InkProblemSeverity.ERROR } },
            problems.toList(),
            evaluatedExpressionCount,
        )
    } catch (failure: InkFailure) {
        problems += failure.problem
        BindingResult(null, problems.toList(), evaluatedExpressionCount)
    }

    private fun renderSiblings(
        nodes: List<TemplateNode>,
        context: EvaluationContext,
        seed: String,
        contextKey: String,
        parentStyle: StyleContext,
    ): List<RenderNode> {
        val rendered = mutableListOf<RenderNode>()
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            if (node is TemplateNode.Element && directive(node, "if") != null) {
                val chain = mutableListOf(node)
                var cursor = index + 1
                while (cursor < nodes.size) {
                    val candidate = nodes[cursor] as? TemplateNode.Element ?: break
                    if (directive(candidate, "elif") != null || directive(candidate, "else") != null) {
                        chain += candidate
                        cursor++
                    } else {
                        break
                    }
                }
                val selected = chain.firstOrNull { candidate ->
                    when {
                        directive(candidate, "else") != null -> true
                        else -> ExpressionEvaluator.truthy(
                            evaluateBinding(
                                directive(candidate, "if") ?: directive(candidate, "elif")!!,
                                context,
                                "$contextKey:condition:${candidate.structuralIndex}",
                            ).value,
                        )
                    }
                }
                if (selected != null) {
                    rendered += renderNode(selected, context, seed, contextKey, parentStyle, ignoreCondition = true)
                }
                index = cursor
                continue
            }
            rendered += renderNode(node, context, seed, contextKey, parentStyle)
            index++
        }
        return rendered
    }

    private fun renderNode(
        node: TemplateNode,
        context: EvaluationContext,
        parentSeed: String,
        contextKey: String,
        parentStyle: StyleContext,
        ignoreCondition: Boolean = false,
        ignoreFor: Boolean = false,
        explicitSeed: String? = null,
    ): List<RenderNode> = when (node) {
        is TemplateNode.Text -> {
            incrementNode()
            val seed = explicitSeed ?: "$parentSeed/t${node.structuralIndex}"
            listOf(
                RenderNode(
                    id = stableNodeId(seed),
                    type = "#text",
                    text = renderString(evaluateBinding(node.value, context, "$contextKey:text:${node.structuralIndex}").value),
                ),
            )
        }
        is TemplateNode.Element -> renderElement(
            node,
            context,
            parentSeed,
            contextKey,
            parentStyle,
            ignoreCondition,
            ignoreFor,
            explicitSeed,
        )
    }

    private fun renderElement(
        node: TemplateNode.Element,
        context: EvaluationContext,
        parentSeed: String,
        contextKey: String,
        parentStyle: StyleContext,
        ignoreCondition: Boolean,
        ignoreFor: Boolean,
        explicitSeed: String?,
    ): List<RenderNode> {
        val loop = directive(node, "for")
        if (!ignoreFor && loop != null) {
            val collection = evaluateBinding(loop, context, "$contextKey:for:${node.structuralIndex}")
            val values = collection.value as? List<*> ?: return emptyList()
            val itemName = directive(node, "for-item")?.literalValue?.takeIf { it.isNotBlank() } ?: "item"
            val indexName = directive(node, "for-index")?.literalValue?.takeIf { it.isNotBlank() } ?: "index"
            val sourcePath = collection.dependencies.singleOrNull()
            val seenKeys = mutableSetOf<String>()
            return values.flatMapIndexed { itemIndex, item ->
                val itemPath = sourcePath?.child(itemIndex)
                val locals = context.locals + mapOf(
                    itemName to LocalValue(item, itemPath),
                    indexName to LocalValue(itemIndex.toDouble(), itemPath?.child("$indexName")),
                )
                val loopContext = EvaluationContext(data, locals)
                var key = resolveLoopKey(node, item, itemIndex, loopContext, "$contextKey:key:${node.structuralIndex}:$itemIndex")
                if (!seenKeys.add(key)) {
                    problems += InkProblem(
                        InkProblemCodes.MARKUP_INVALID,
                        "Duplicate for key '$key'",
                        line = node.location.line,
                        column = node.location.column,
                        feature = key,
                    )
                    key = "$key#$itemIndex"
                }
                val loopSeed = "$parentSeed/e${node.structuralIndex}[$key]"
                renderNode(
                    node,
                    loopContext,
                    parentSeed,
                    "$contextKey/${node.structuralIndex}=$key",
                    parentStyle,
                    ignoreCondition,
                    ignoreFor = true,
                    explicitSeed = loopSeed,
                )
            }
        }

        if (!ignoreCondition) {
            val condition = directive(node, "if") ?: directive(node, "elif")
            if (condition != null && !ExpressionEvaluator.truthy(
                    evaluateBinding(condition, context, "$contextKey:condition:${node.structuralIndex}").value,
                )
            ) {
                return emptyList()
            }
        }

        incrementNode()
        val seed = explicitSeed ?: "$parentSeed/e${node.structuralIndex}"
        val classValue = node.attributes["class"]?.let {
            renderString(evaluateBinding(it, context, "$contextKey:class:${node.structuralIndex}").value)
        }.orEmpty()
        val classes = classValue.split(Regex("\\s+")).filter(String::isNotBlank).toSet()
        val inlineStyle = node.attributes["style"]?.let {
            renderString(evaluateBinding(it, context, "$contextKey:style:${node.structuralIndex}").value)
        }.orEmpty()
        val resolvedStyle = resolveStyle(classes, inlineStyle, parentStyle, node)
        val attributes = linkedMapOf<String, Any?>()
        val events = linkedMapOf<String, InkActionBinding>()
        val dataset = linkedMapOf<String, Any?>()
        node.attributes.toSortedMap().forEach { (name, binding) ->
            when {
                name == "class" || name == "style" || WxmlParser.normalizeDirective(name) != null -> Unit
                name.startsWith("data-") -> dataset[name.removePrefix("data-")] =
                    evaluateBinding(binding, context, "$contextKey:dataset:${node.structuralIndex}:$name").value.deepCopyInk()
                name.startsWith("bind") || name.startsWith("catch") -> {
                    val catches = name.startsWith("catch")
                    val event = name.removePrefix(if (catches) "catch" else "bind")
                    val action = binding.literalValue.orEmpty()
                    events[event] = InkActionBinding(action, catches)
                }
                else -> attributes[name] = normalizeAttribute(
                    node.tag,
                    name,
                    evaluateBinding(binding, context, "$contextKey:attr:${node.structuralIndex}:$name").value,
                ).deepCopyInk()
            }
        }
        val children = renderSiblings(node.children, context, seed, contextKey, resolvedStyle.context)
        return listOf(
            RenderNode(
                id = stableNodeId(seed),
                type = node.tag,
                attributes = attributes.toSortedMap(),
                style = resolvedStyle.values.toSortedMap(),
                events = events.toSortedMap(),
                dataset = dataset.toSortedMap(),
                children = children,
            ),
        )
    }

    private fun resolveLoopKey(
        node: TemplateNode.Element,
        item: Any?,
        index: Int,
        context: EvaluationContext,
        contextKey: String,
    ): String {
        val binding = directive(node, "key") ?: return index.toString()
        binding.exactExpression?.let { return renderString(evaluateExpression(it, context, contextKey).value) }
        val keyName = binding.literalValue.orEmpty()
        if (keyName == "*this") return renderString(item)
        val value = (item as? Map<*, *>)?.get(keyName)
        return renderString(value).ifEmpty { index.toString() }
    }

    private fun resolveStyle(
        classes: Set<String>,
        inlineStyle: String,
        parent: StyleContext,
        node: TemplateNode.Element,
    ): ResolvedStyle {
        val variables = LinkedHashMap(parent.variables)
        val own = linkedMapOf<String, String>()
        rules.asSequence().filter { it.className in classes }.forEach { rule ->
            rule.declarations.forEach { (property, value) ->
                if (property.startsWith("--")) variables[property] = value.cssText else own[property] = value.cssText
            }
        }
        if (inlineStyle.isNotBlank()) {
            val parser = WxssParser(inlineStyle, node.location)
            val (declarations, styleProblems) = parser.parseInline(inlineStyle)
            styleProblems.forEach { problem ->
                val key = listOf(problem.code, problem.feature, problem.line, problem.column).joinToString("|")
                if (cache.reportedStyleProblems.add(key)) problems += problem
            }
            declarations.forEach { (property, value) ->
                if (property.startsWith("--")) variables[property] = value.cssText else own[property] = value.cssText
            }
        }
        val values = linkedMapOf<String, String>()
        parent.inherited.forEach { (property, value) -> values[property] = value }
        own.forEach { (property, value) -> values[property] = resolveVariables(value, variables) }
        val inherited = values.filterKeys { it in INHERITED_PROPERTIES }
        return ResolvedStyle(values, StyleContext(variables, inherited))
    }

    private fun evaluateBinding(
        binding: BindingValue,
        context: EvaluationContext,
        contextKey: String,
    ): EvaluatedBinding {
        binding.exactExpression?.let { return evaluateExpression(it, context, contextKey) }
        val dependencies = linkedSetOf<DataPath>()
        val value = buildString {
            binding.parts.forEach { part ->
                when (part) {
                    is BindingPart.Literal -> append(part.value)
                    is BindingPart.Expression -> {
                        val evaluated = evaluateExpression(part, context, "$contextKey:${part.id}")
                        dependencies += evaluated.dependencies
                        append(renderString(evaluated.value))
                    }
                }
            }
        }
        return EvaluatedBinding(value, dependencies)
    }

    private fun evaluateExpression(
        expression: BindingPart.Expression,
        context: EvaluationContext,
        contextKey: String,
    ): EvaluatedBinding {
        val key = "${expression.id}|$contextKey"
        val cached = cache.expressions[key]
        if (cached != null && dirtyPaths.none { dirty -> cached.dependencies.any(dirty::intersects) }) {
            return EvaluatedBinding(cached.value, cached.dependencies)
        }
        return try {
            val evaluated = evaluator.evaluate(expression.ast, context)
            evaluatedExpressionCount++
            cache.expressions[key] = CachedExpression(evaluated.value.deepCopyInk(), evaluated.dependencies)
            EvaluatedBinding(evaluated.value, evaluated.dependencies)
        } catch (error: ExpressionException) {
            problems += InkProblem(error.code, error.message, feature = expression.source)
            EvaluatedBinding(null, emptySet())
        }
    }

    private fun incrementNode() {
        nodeCount++
        if (nodeCount > MAX_NODES) {
            throw InkFailure(
                InkProblem(
                    InkProblemCodes.BUDGET_NODES,
                    "Expanded document exceeds the $MAX_NODES node budget",
                    feature = MAX_NODES.toString(),
                ),
            )
        }
    }

    private fun normalizeAttribute(tag: String, name: String, value: Any?): Any? {
        if (tag == "scroll-view") return when (name) {
            "scroll-x", "scroll-y", "auto-scroll" -> when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                is Number -> value.toInt() != 0
                else -> false
            }
            "scroll-top", "scroll-left", "scroll-speed" -> when (value) {
                is Number -> value
                is String -> value.toDoubleOrNull() ?: value
                else -> value
            }
            else -> value
        }
        val booleanAttributes = when (tag) {
            "chart" -> setOf("animate", "smooth", "show-average", "showAverage", "show-value-labels")
            "lottie-view" -> setOf("auto-play", "loop")
            "progress" -> setOf("show-info", "active")
            "nx-canvas" -> setOf("loop")
            else -> emptySet()
        }
        if (name in booleanAttributes) return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> value
        }
        val numberAttributes = when (tag) {
            "chart" -> setOf("width", "height")
            "lottie-view" -> setOf("speed", "progress")
            "progress" -> setOf("percent", "stroke-width", "duration")
            "nx-canvas" -> setOf("fps", "width", "height")
            else -> emptySet()
        }
        if (name in numberAttributes && value is String) return value.toDoubleOrNull() ?: value
        val jsonAttributes = when (tag) {
            "chart" -> setOf("data", "series", "y-axis", "yAxis", "x-axis", "xAxis")
            "nx-canvas" -> setOf("commands", "frames")
            else -> emptySet()
        }
        if (name in jsonAttributes && value is String) return parseAttributeJson(value) ?: value
        return value
    }

    private fun parseAttributeJson(value: String): Any? = runCatching {
        val trimmed = value.trim()
        when {
            trimmed.startsWith("{") -> org.json.JSONObject(trimmed).toInkObject()
            trimmed.startsWith("[") -> org.json.JSONObject().put("value", org.json.JSONArray(trimmed)).toInkObject()["value"]
            else -> null
        }
    }.getOrNull()

    private fun directive(node: TemplateNode.Element, name: String): BindingValue? =
        WxmlParser.findDirective(node.attributes, name)

    private data class EvaluatedBinding(val value: Any?, val dependencies: Set<DataPath>)
    private data class StyleContext(
        val variables: Map<String, String> = emptyMap(),
        val inherited: Map<String, String> = emptyMap(),
    )
    private data class ResolvedStyle(val values: Map<String, String>, val context: StyleContext)

    private companion object {
        const val MAX_NODES = 256
        val INHERITED_PROPERTIES = setOf(
            "color",
            "font-size",
            "font-weight",
            "line-height",
            "text-align",
            "white-space",
        )
    }
}

private fun resolveVariables(value: String, variables: Map<String, String>, depth: Int = 0): String {
    if (depth >= 16) return value
    val start = value.indexOf("var(")
    if (start < 0) return value
    var cursor = start + 4
    var nesting = 1
    while (cursor < value.length && nesting > 0) {
        when (value[cursor]) {
            '(' -> nesting++
            ')' -> nesting--
        }
        cursor++
    }
    if (nesting != 0) return value
    val body = value.substring(start + 4, cursor - 1)
    val comma = findTopLevelComma(body)
    val name = body.substring(0, comma.takeIf { it >= 0 } ?: body.length).trim()
    val fallback = comma.takeIf { it >= 0 }?.let { body.substring(it + 1).trim() }
    val replacement = variables[name]?.let { resolveVariables(it, variables, depth + 1) }
        ?: fallback?.let { resolveVariables(it, variables, depth + 1) }
        ?: value.substring(start, cursor)
    return resolveVariables(value.substring(0, start) + replacement + value.substring(cursor), variables, depth + 1)
}

private fun findTopLevelComma(value: String): Int {
    var depth = 0
    value.forEachIndexed { index, char ->
        when (char) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) return index
        }
    }
    return -1
}

private fun stableNodeId(seed: String): String {
    var hash = -3750763034362895579L
    seed.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 1099511628211L
    }
    return "n${java.lang.Long.toUnsignedString(hash, 36)}"
}
