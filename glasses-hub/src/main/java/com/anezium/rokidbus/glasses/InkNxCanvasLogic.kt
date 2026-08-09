package com.anezium.rokidbus.glasses

internal data class InkCanvasCommand(
    val name: String,
    val args: List<Any?>,
    val id: String? = null,
    val target: String? = null,
)

internal data class InkCanvasProgram(
    val frames: List<List<InkCanvasCommand>>,
    val fps: Int,
    val loop: Boolean,
) {
    val isSequence: Boolean get() = frames.size > 1
}

internal object InkNxCanvasLogic {
    const val MAX_FPS = 30

    fun parse(attributes: Map<String, Any?>): InkCanvasProgram? {
        val rawFrames = attributes["frames"] as? List<*>
        val frames = if (rawFrames != null) {
            rawFrames.map { parseCommands(it) ?: return null }
        } else {
            listOf(parseCommands(attributes["commands"] ?: emptyList<Any?>()) ?: return null)
        }
        return InkCanvasProgram(
            frames = frames,
            fps = ((attributes["fps"] as? Number)?.toInt() ?: MAX_FPS).coerceIn(1, MAX_FPS),
            loop = attributes["loop"] as? Boolean ?: true,
        )
    }

    fun validateCommands(raw: Any?): List<String> {
        val values = raw as? List<*> ?: return listOf("commands must be an array")
        return buildList {
            values.forEachIndexed { index, value ->
                val command = value as? Map<*, *>
                if (command == null) {
                    add("command $index must be an object")
                    return@forEachIndexed
                }
                val name = command["name"] ?: command["op"]
                if (name !is String || name !in SUPPORTED) add("command $index has unsupported name '$name'")
                if (command["args"] != null && command["args"] !is List<*>) add("command $index args must be an array")
            }
        }
    }

    private fun parseCommands(raw: Any?): List<InkCanvasCommand>? {
        val values = raw as? List<*> ?: return null
        if (validateCommands(values).isNotEmpty()) return null
        return values.map { value ->
            val command = value as Map<*, *>
            InkCanvasCommand(
                name = (command["name"] ?: command["op"]).toString(),
                args = command["args"] as? List<*> ?: emptyList<Any?>(),
                id = command["id"]?.toString(),
                target = command["target"]?.toString(),
            )
        }
    }

    private val SUPPORTED = setOf(
        "fillStyle", "strokeStyle", "lineWidth", "lineCap", "lineJoin", "lineDashOffset", "globalAlpha",
        "font", "textAlign", "textBaseline", "fillRect", "strokeRect", "clearRect", "beginPath", "moveTo",
        "lineTo", "arc", "rect", "ellipse", "arcTo", "bezierCurveTo", "quadraticCurveTo", "closePath",
        "roundRect", "clip", "fill", "stroke", "fillText", "strokeText", "save", "restore", "translate",
        "rotate", "scale", "transform", "setTransform", "resetTransform", "setLineDash", "createLinearGradient",
        "createRadialGradient", "addColorStop",
    )
}
