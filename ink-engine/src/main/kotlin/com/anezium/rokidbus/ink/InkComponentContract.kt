package com.anezium.rokidbus.ink

import java.nio.charset.StandardCharsets

/** The strict rich-component subset shared by the compiler and glasses wire edge. */
internal object InkComponentContract {
    const val MAX_CHART_SERIES = 4
    const val MAX_CHART_POINTS = 256
    const val MAX_CANVAS_COMMANDS = 512
    const val MAX_LOTTIE_JSON_BYTES = 32 * 1024
    const val MAX_CANVAS_FPS = 30

    val supportedComponents = setOf(
        "view",
        "text",
        "image",
        "scroll-view",
        "chart",
        "lottie-view",
        "progress",
        "nx-canvas",
    )

    private val commonAttributes = setOf("id")
    private val chartAttributes = setOf(
        "type",
        "series",
        "data",
        "width",
        "height",
        "animate",
        "color",
        "show-average",
        "showAverage",
        "smooth",
        "y-axis",
        "yAxis",
        "x-axis",
        "xAxis",
        // The official component contract omits bar. These three attributes are
        // retained only for the explicitly sample-derived bar extension.
        "direction",
        "show-value-labels",
        "value-label-format",
    )
    private val attributes = mapOf(
        "view" to commonAttributes,
        "text" to commonAttributes,
        "image" to commonAttributes + setOf("src", "mode"),
        "scroll-view" to commonAttributes + setOf(
            "scroll-x",
            "scroll-y",
            "scroll-top",
            "scroll-left",
            "scroll-into-view",
            "auto-scroll",
            "scroll-speed",
            "scroll-direction",
        ),
        "chart" to commonAttributes + chartAttributes,
        "lottie-view" to commonAttributes + setOf("src", "auto-play", "loop", "speed", "progress"),
        "progress" to commonAttributes + setOf(
            "percent",
            "show-info",
            "stroke-width",
            "active",
            "duration",
            "color",
            "background-color",
        ),
        "nx-canvas" to commonAttributes + setOf("commands", "frames", "fps", "loop", "width", "height"),
    )

    val leafComponents = setOf("image", "chart", "lottie-view", "progress", "nx-canvas")

    fun attributesFor(component: String): Set<String> = attributes[component].orEmpty()

    fun validate(node: RenderNode, path: String): List<InkProblem> = buildList {
        when (node.type) {
            "chart" -> validateChart(node.attributes, path, this)
            "lottie-view" -> validateLottie(node.attributes, path, this)
            "progress" -> validateProgress(node.attributes, path, this)
            "nx-canvas" -> validateCanvas(node.attributes, path, this)
        }
        if (node.type in leafComponents && node.children.isNotEmpty()) {
            add(componentValue("<${node.type}> is a leaf component and cannot have children", "$path.c"))
        }
    }

    fun compatibilityWarnings(document: RenderDocument): List<InkProblem> = buildList {
        fun visit(node: RenderNode) {
            if (node.type == "chart" && node.attributes["type"] == "bar") {
                add(
                    InkProblem(
                        InkProblemCodes.COMPONENT_SAMPLE_DERIVED,
                        "Chart type 'bar' is a sample-derived Nexus extension, not part of the guaranteed official chart contract",
                        InkProblemSeverity.WARNING,
                        feature = "chart.type",
                    ),
                )
            }
            node.children.forEach(::visit)
        }
        document.roots.forEach(::visit)
    }.distinct()

    private fun validateChart(
        attributes: Map<String, Any?>,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        val type = attributes["type"] ?: "line"
        if (type !is String) {
            problems += attributeType("Chart attribute 'type' must be a string", "$path.a.type")
        } else if (type !in CHART_TYPES) {
            problems += componentValue("Chart type '$type' is outside the Ink v1 chart matrix", "$path.a.type")
        }
        validateNumber(attributes, "width", path, problems, minimum = 1.0, maximum = 2_048.0)
        validateNumber(attributes, "height", path, problems, minimum = 1.0, maximum = 2_048.0)
        listOf("animate", "smooth", "show-average", "showAverage", "show-value-labels").forEach {
            validateBoolean(attributes, it, path, problems)
        }
        listOf("color", "direction", "value-label-format").forEach { validateString(attributes, it, path, problems) }
        listOf("y-axis", "yAxis", "x-axis", "xAxis").forEach { name ->
            attributes[name]?.let { value ->
                if (value !is Map<*, *>) {
                    problems += attributeType("Chart attribute '$name' must be an object or JSON object string", "$path.a.$name")
                }
            }
        }

        val data = attributes["data"] ?: emptyList<Any?>()
        validatePointList(data, "$path.a.data", problems)

        when (val series = attributes["series"] ?: "value") {
            is String -> if (series.isBlank()) {
                problems += attributeValue("Chart series field name cannot be blank", "$path.a.series")
            }
            is List<*> -> {
                if (series.isEmpty()) {
                    problems += attributeValue("Chart series array cannot be empty", "$path.a.series")
                }
                if (series.size > MAX_CHART_SERIES) {
                    problems += componentBudget(
                        "Chart has ${series.size} series; maximum is $MAX_CHART_SERIES",
                        "$path.a.series",
                    )
                }
                series.forEachIndexed { index, raw ->
                    val itemPath = "$path.a.series[$index]"
                    val item = raw as? Map<*, *>
                    if (item == null) {
                        problems += attributeType("Chart series entries must be objects", itemPath)
                        return@forEachIndexed
                    }
                    val yName = item["yName"] ?: item["yKey"]
                    if (yName !is String || yName.isBlank()) {
                        problems += attributeRequired("Chart series requires a non-blank yName or yKey", itemPath)
                    }
                    listOf("xName", "xKey", "color").forEach { nested ->
                        if (item.containsKey(nested) && item[nested] !is String) {
                            problems += attributeType("Chart series '$nested' must be a string", "$itemPath.$nested")
                        }
                    }
                    item["width"]?.let { value ->
                        if (!value.isFiniteNumber() || value.asDouble() !in 0.1..64.0) {
                            problems += attributeValue("Chart series width must be between 0.1 and 64", "$itemPath.width")
                        }
                    }
                    item["smooth"]?.let { value ->
                        if (value !is Boolean) problems += attributeType("Chart series smooth must be boolean", "$itemPath.smooth")
                    }
                    item["dataSource"]?.let { validatePointList(it, "$itemPath.dataSource", problems) }
                }
            }
            else -> problems += attributeType("Chart series must be a field name or array", "$path.a.series")
        }
    }

    private fun validatePointList(value: Any?, path: String, problems: MutableList<InkProblem>) {
        val points = value as? List<*>
        if (points == null) {
            problems += attributeType("Chart data must be an array", path)
            return
        }
        if (points.size > MAX_CHART_POINTS) {
            problems += componentBudget(
                "Chart data has ${points.size} points; maximum is $MAX_CHART_POINTS per series",
                path,
            )
        }
        points.forEachIndexed { index, point ->
            if (point !is Map<*, *>) problems += attributeType("Chart data points must be objects", "$path[$index]")
        }
    }

    private fun validateLottie(
        attributes: Map<String, Any?>,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        listOf("auto-play", "loop").forEach { validateBoolean(attributes, it, path, problems) }
        validateNumber(attributes, "speed", path, problems, minimum = -4.0, maximum = 4.0, excludeZero = true)
        validateNumber(attributes, "progress", path, problems, minimum = 0.0, maximum = 1.0)
        val source = attributes["src"] ?: return
        if (source !is String) {
            problems += attributeType("Lottie src must be an inline JSON string", "$path.a.src")
            return
        }
        if (source.isBlank()) return
        val size = source.toByteArray(StandardCharsets.UTF_8).size
        if (size > MAX_LOTTIE_JSON_BYTES) {
            problems += componentBudget(
                "Inline Lottie JSON is $size bytes; maximum is $MAX_LOTTIE_JSON_BYTES bytes",
                "$path.a.src",
            )
            return
        }
        val trimmed = source.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            problems += InkProblem(
                InkProblemCodes.ATTRIBUTE_SOURCE,
                "Lottie src must contain inline JSON; paths and URLs are not supported",
                feature = "$path.a.src",
            )
        } else {
            runCatching { org.json.JSONObject(trimmed) }.onFailure {
                problems += attributeValue("Lottie src is not valid inline JSON", "$path.a.src")
            }
        }
    }

    private fun validateProgress(
        attributes: Map<String, Any?>,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        validateNumber(attributes, "percent", path, problems, minimum = 0.0, maximum = 100.0)
        validateNumber(attributes, "stroke-width", path, problems, minimum = 1.0, maximum = 64.0)
        validateNumber(attributes, "duration", path, problems, minimum = 0.0, maximum = 5_000.0)
        listOf("show-info", "active").forEach { validateBoolean(attributes, it, path, problems) }
        listOf("color", "background-color").forEach { validateString(attributes, it, path, problems) }
    }

    private fun validateCanvas(
        attributes: Map<String, Any?>,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        validateNumber(attributes, "width", path, problems, minimum = 1.0, maximum = 2_048.0)
        validateNumber(attributes, "height", path, problems, minimum = 1.0, maximum = 2_048.0)
        validateNumber(attributes, "fps", path, problems, minimum = 1.0, maximum = MAX_CANVAS_FPS.toDouble())
        validateBoolean(attributes, "loop", path, problems)
        val commands = attributes["commands"]
        val frames = attributes["frames"]
        if (commands != null && frames != null) {
            problems += attributeValue("nx-canvas accepts commands or frames, not both", "$path.a")
            return
        }
        var commandCount = 0
        if (frames != null) {
            val sequence = frames as? List<*>
            if (sequence == null) {
                problems += attributeType("nx-canvas frames must be an array of command arrays", "$path.a.frames")
            } else {
                sequence.forEachIndexed { index, frame ->
                    commandCount += validateCommandArray(frame, "$path.a.frames[$index]", problems)
                }
            }
        } else {
            commandCount = validateCommandArray(commands ?: emptyList<Any?>(), "$path.a.commands", problems)
        }
        if (commandCount > MAX_CANVAS_COMMANDS) {
            problems += componentBudget(
                "nx-canvas has $commandCount commands; maximum is $MAX_CANVAS_COMMANDS",
                "$path.a.${if (frames != null) "frames" else "commands"}",
            )
        }
    }

    private fun validateCommandArray(
        raw: Any?,
        path: String,
        problems: MutableList<InkProblem>,
    ): Int {
        val commands = raw as? List<*>
        if (commands == null) {
            problems += attributeType("nx-canvas commands must be an array", path)
            return 0
        }
        commands.forEachIndexed { index, value ->
            val commandPath = "$path[$index]"
            val command = value as? Map<*, *>
            if (command == null) {
                problems += attributeType("nx-canvas command must be an object", commandPath)
                return@forEachIndexed
            }
            val name = command["name"] ?: command["op"]
            if (name !is String) {
                problems += attributeRequired("nx-canvas command requires a string name", commandPath)
                return@forEachIndexed
            }
            val arity = CANVAS_COMMAND_ARITY[name]
            if (arity == null) {
                problems += componentValue("Canvas operation '$name' is outside the declarative v1 vocabulary", "$commandPath.name")
                return@forEachIndexed
            }
            val args = command["args"] ?: emptyList<Any?>()
            val values = args as? List<*>
            if (values == null) {
                problems += attributeType("Canvas operation '$name' args must be an array", "$commandPath.args")
            } else if (values.size !in arity) {
                problems += attributeValue(
                    "Canvas operation '$name' expects ${arity.first}..${arity.last} arguments, found ${values.size}",
                    "$commandPath.args",
                )
            }
        }
        return commands.size
    }

    private fun validateBoolean(
        attributes: Map<String, Any?>,
        name: String,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        if (attributes.containsKey(name) && attributes[name] !is Boolean) {
            problems += attributeType("Attribute '$name' must be boolean", "$path.a.$name")
        }
    }

    private fun validateString(
        attributes: Map<String, Any?>,
        name: String,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        if (attributes.containsKey(name) && attributes[name] !is String) {
            problems += attributeType("Attribute '$name' must be a string", "$path.a.$name")
        }
    }

    private fun validateNumber(
        attributes: Map<String, Any?>,
        name: String,
        path: String,
        problems: MutableList<InkProblem>,
        minimum: Double,
        maximum: Double,
        excludeZero: Boolean = false,
    ) {
        if (!attributes.containsKey(name)) return
        val value = attributes[name]
        if (!value.isFiniteNumber()) {
            problems += attributeType("Attribute '$name' must be a finite number", "$path.a.$name")
            return
        }
        val number = value.asDouble()
        if (number !in minimum..maximum || excludeZero && number == 0.0) {
            problems += attributeValue("Attribute '$name' must be between $minimum and $maximum", "$path.a.$name")
        }
    }

    private fun Any?.isFiniteNumber(): Boolean = this is Number && toDouble().isFinite()
    private fun Any?.asDouble(): Double = (this as Number).toDouble()

    private fun componentValue(message: String, feature: String) =
        InkProblem(InkProblemCodes.COMPONENT_VALUE, message, feature = feature)

    private fun componentBudget(message: String, feature: String) =
        InkProblem(InkProblemCodes.COMPONENT_BUDGET, message, feature = feature)

    private fun attributeRequired(message: String, feature: String) =
        InkProblem(InkProblemCodes.ATTRIBUTE_REQUIRED, message, feature = feature)

    private fun attributeType(message: String, feature: String) =
        InkProblem(InkProblemCodes.ATTRIBUTE_TYPE, message, feature = feature)

    private fun attributeValue(message: String, feature: String) =
        InkProblem(InkProblemCodes.ATTRIBUTE_VALUE, message, feature = feature)

    private val CHART_TYPES = setOf("line", "area", "pie", "radar", "bar")

    private val CANVAS_COMMAND_ARITY = mapOf(
        "fillStyle" to 1..1,
        "strokeStyle" to 1..1,
        "lineWidth" to 1..1,
        "lineCap" to 1..1,
        "lineJoin" to 1..1,
        "lineDashOffset" to 1..1,
        "globalAlpha" to 1..1,
        "font" to 1..1,
        "textAlign" to 1..1,
        "textBaseline" to 1..1,
        "fillRect" to 4..4,
        "strokeRect" to 4..4,
        "clearRect" to 4..4,
        "beginPath" to 0..0,
        "moveTo" to 2..2,
        "lineTo" to 2..2,
        "arc" to 5..6,
        "rect" to 4..4,
        "ellipse" to 7..8,
        "arcTo" to 5..5,
        "bezierCurveTo" to 6..6,
        "quadraticCurveTo" to 4..4,
        "closePath" to 0..0,
        "roundRect" to 5..8,
        "clip" to 0..1,
        "fill" to 0..1,
        "stroke" to 0..0,
        "fillText" to 3..4,
        "strokeText" to 3..4,
        "save" to 0..0,
        "restore" to 0..0,
        "translate" to 2..2,
        "rotate" to 1..1,
        "scale" to 2..2,
        "transform" to 6..6,
        "setTransform" to 6..6,
        "resetTransform" to 0..0,
        "setLineDash" to 1..1,
        "createLinearGradient" to 4..4,
        "createRadialGradient" to 6..6,
        "addColorStop" to 2..2,
    )
}
