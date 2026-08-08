package com.anezium.rokidbus.glasses

import kotlin.math.roundToInt

internal object InkLengthResolver {
    private val dimension = Regex("^([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))(px|rpx|%)?$", RegexOption.IGNORE_CASE)

    fun resolve(
        cssValue: String?,
        percentBasePx: Float,
        inkContainerWidthPx: Float,
        density: Float,
    ): Float? {
        val match = cssValue?.trim()?.let(dimension::matchEntire) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "rpx" -> value * inkContainerWidthPx / 750f
            "%" -> value * percentBasePx / 100f
            else -> value * density
        }
    }
}

internal enum class InkFlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
internal enum class InkFlexWrap { NOWRAP, WRAP, WRAP_REVERSE }
internal enum class InkJustify { START, END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
internal enum class InkAlign { AUTO, START, END, CENTER, BASELINE, STRETCH }

internal data class InkFlexStyle(
    val direction: InkFlexDirection = InkFlexDirection.ROW,
    val wrap: InkFlexWrap = InkFlexWrap.NOWRAP,
    val justify: InkJustify = InkJustify.START,
    val alignItems: InkAlign = InkAlign.STRETCH,
    val alignSelf: InkAlign = InkAlign.AUTO,
    val grow: Float = 0f,
    val shrink: Float = 1f,
    val basis: String? = null,
    val gap: String? = null,
) {
    companion object {
        fun from(style: Map<String, String>): InkFlexStyle {
            val shorthand = parseFlex(style["flex"])
            return InkFlexStyle(
                direction = when (style["flex-direction"]?.lowercase()) {
                    "row-reverse" -> InkFlexDirection.ROW_REVERSE
                    "column" -> InkFlexDirection.COLUMN
                    "column-reverse" -> InkFlexDirection.COLUMN_REVERSE
                    else -> InkFlexDirection.ROW
                },
                wrap = when (style["flex-wrap"]?.lowercase()) {
                    "wrap" -> InkFlexWrap.WRAP
                    "wrap-reverse" -> InkFlexWrap.WRAP_REVERSE
                    else -> InkFlexWrap.NOWRAP
                },
                justify = when (style["justify-content"]?.lowercase()) {
                    "flex-end", "end" -> InkJustify.END
                    "center" -> InkJustify.CENTER
                    "space-between" -> InkJustify.SPACE_BETWEEN
                    "space-around" -> InkJustify.SPACE_AROUND
                    "space-evenly" -> InkJustify.SPACE_EVENLY
                    else -> InkJustify.START
                },
                alignItems = parseAlign(style["align-items"], InkAlign.STRETCH),
                alignSelf = parseAlign(style["align-self"], InkAlign.AUTO),
                grow = style["flex-grow"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: shorthand.grow,
                shrink = style["flex-shrink"]?.toFloatOrNull()?.coerceAtLeast(0f) ?: shorthand.shrink,
                basis = style["flex-basis"] ?: shorthand.basis,
                gap = style["gap"],
            )
        }

        private fun parseAlign(value: String?, fallback: InkAlign): InkAlign = when (value?.lowercase()) {
            "auto" -> InkAlign.AUTO
            "flex-start", "start" -> InkAlign.START
            "flex-end", "end" -> InkAlign.END
            "center" -> InkAlign.CENTER
            "baseline" -> InkAlign.BASELINE
            "stretch" -> InkAlign.STRETCH
            else -> fallback
        }

        private fun parseFlex(value: String?): FlexShorthand {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty() || normalized == "initial") return FlexShorthand(0f, 1f, null)
            if (normalized == "none") return FlexShorthand(0f, 0f, "auto")
            if (normalized == "auto") return FlexShorthand(1f, 1f, "auto")
            val parts = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
            val grow = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val secondNumber = parts.getOrNull(1)?.toFloatOrNull()
            val shrink = secondNumber ?: 1f
            val basis = parts.getOrNull(if (secondNumber != null) 2 else 1)
            return FlexShorthand(grow.coerceAtLeast(0f), shrink.coerceAtLeast(0f), basis)
        }

        private data class FlexShorthand(val grow: Float, val shrink: Float, val basis: String?)
    }
}

internal data class InkBoxEdges<T>(val top: T, val right: T, val bottom: T, val left: T)

internal object InkBoxStyle {
    fun rawEdges(style: Map<String, String>, prefix: String, default: String = "0px"): InkBoxEdges<String> {
        val shorthand = expand(style[prefix]?.split(Regex("\\s+")).orEmpty().filter(String::isNotBlank), default)
        return InkBoxEdges(
            top = style["$prefix-top"] ?: shorthand.top,
            right = style["$prefix-right"] ?: shorthand.right,
            bottom = style["$prefix-bottom"] ?: shorthand.bottom,
            left = style["$prefix-left"] ?: shorthand.left,
        )
    }

    private fun expand(values: List<String>, default: String): InkBoxEdges<String> = when (values.size) {
        1 -> InkBoxEdges(values[0], values[0], values[0], values[0])
        2 -> InkBoxEdges(values[0], values[1], values[0], values[1])
        3 -> InkBoxEdges(values[0], values[1], values[2], values[1])
        in 4..Int.MAX_VALUE -> InkBoxEdges(values[0], values[1], values[2], values[3])
        else -> InkBoxEdges(default, default, default, default)
    }
}

internal data class InkColorPalette(
    val phosphor: Int,
    val text: Int,
    val muted: Int,
    val dim: Int,
    val danger: Int,
    val black: Int,
)

internal enum class InkColorTier { PHOSPHOR, TEXT, MUTED, DIM, DANGER, BLACK }

internal data class InkResolvedColor(val color: Int, val tier: InkColorTier, val wasLiteral: Boolean)

internal object InkColorClamp {
    fun resolve(value: String?, palette: InkColorPalette, fallback: InkColorTier): InkResolvedColor {
        val normalized = value?.trim()?.lowercase().orEmpty()
        tokenTier(normalized)?.let { return resolved(it, palette, wasLiteral = false) }
        if (normalized == "transparent") return resolved(InkColorTier.BLACK, palette, wasLiteral = false)
        val literal = parseColor(normalized) ?: return resolved(fallback, palette, wasLiteral = false)
        val candidates = listOf(InkColorTier.PHOSPHOR, InkColorTier.MUTED, InkColorTier.DIM, InkColorTier.DANGER)
        val nearest = candidates.minBy { colorDistance(literal, color(it, palette)) }
        return resolved(nearest, palette, wasLiteral = true)
    }

    private fun tokenTier(value: String): InkColorTier? = when {
        "danger" in value || "error" in value -> InkColorTier.DANGER
        "muted" in value || "secondary" in value -> InkColorTier.MUTED
        "dim" in value || "disabled" in value -> InkColorTier.DIM
        "text" in value -> InkColorTier.TEXT
        "black" in value || "background" in value || value == "var(--color-bg)" -> InkColorTier.BLACK
        "primary" in value || "phosphor" in value || "accent" in value || "green" in value -> InkColorTier.PHOSPHOR
        else -> null
    }

    private fun parseColor(value: String): Int? {
        if (value.startsWith("#")) {
            val hex = value.removePrefix("#")
            return when (hex.length) {
                3, 4 -> {
                    val red = "${hex[0]}${hex[0]}".toIntOrNull(16) ?: return null
                    val green = "${hex[1]}${hex[1]}".toIntOrNull(16) ?: return null
                    val blue = "${hex[2]}${hex[2]}".toIntOrNull(16) ?: return null
                    rgb(red, green, blue)
                }
                6, 8 -> {
                    val red = hex.substring(0, 2).toIntOrNull(16) ?: return null
                    val green = hex.substring(2, 4).toIntOrNull(16) ?: return null
                    val blue = hex.substring(4, 6).toIntOrNull(16) ?: return null
                    rgb(red, green, blue)
                }
                else -> null
            }
        }
        val rgbMatch = Regex("rgba?\\((.*)\\)", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
        val parts = rgbMatch.groupValues[1].split(',').map(String::trim)
        if (parts.size !in 3..4) return null
        val channels = parts.take(3).map { part ->
            if (part.endsWith('%')) {
                (part.removeSuffix("%").toFloatOrNull()?.coerceIn(0f, 100f)?.times(2.55f))?.roundToInt()
            } else {
                part.toFloatOrNull()?.coerceIn(0f, 255f)?.roundToInt()
            }
        }
        if (channels.any { it == null }) return null
        return rgb(channels[0]!!, channels[1]!!, channels[2]!!)
    }

    private fun colorDistance(left: Int, right: Int): Long {
        val red = channel(left, 16) - channel(right, 16)
        val green = channel(left, 8) - channel(right, 8)
        val blue = channel(left, 0) - channel(right, 0)
        return red.toLong() * red + green.toLong() * green + blue.toLong() * blue
    }

    private fun resolved(tier: InkColorTier, palette: InkColorPalette, wasLiteral: Boolean) =
        InkResolvedColor(color(tier, palette), tier, wasLiteral)

    private fun color(tier: InkColorTier, palette: InkColorPalette): Int = when (tier) {
        InkColorTier.PHOSPHOR -> palette.phosphor
        InkColorTier.TEXT -> palette.text
        InkColorTier.MUTED -> palette.muted
        InkColorTier.DIM -> palette.dim
        InkColorTier.DANGER -> palette.danger
        InkColorTier.BLACK -> palette.black
    }

    private fun channel(color: Int, shift: Int): Int = color ushr shift and 0xff
    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}

internal data class InkTransitionSpec(
    val property: String,
    val durationMs: Long,
    val delayMs: Long,
    val easing: String,
)

internal class InkTransitionTable private constructor(private val specs: Map<String, InkTransitionSpec>) {
    fun forProperty(property: String): InkTransitionSpec? = specs[property] ?: specs["all"]?.copy(property = property)

    companion object {
        fun from(style: Map<String, String>): InkTransitionTable {
            val shorthand = style["transition"]?.let(::parseShorthand).orEmpty()
            if (shorthand.isNotEmpty()) return InkTransitionTable(shorthand.associateBy(InkTransitionSpec::property))
            val properties = style["transition-property"]?.split(',')?.map(String::trim).orEmpty()
            if (properties.isEmpty()) return InkTransitionTable(emptyMap())
            val durations = style["transition-duration"]?.split(',')?.map { parseTime(it.trim()) }.orEmpty()
            val delays = style["transition-delay"]?.split(',')?.map { parseTime(it.trim()) }.orEmpty()
            val easings = style["transition-timing-function"]?.split(',')?.map(String::trim).orEmpty()
            return InkTransitionTable(
                properties.mapIndexed { index, property ->
                    InkTransitionSpec(
                        property,
                        durations.getOrElse(index) { durations.lastOrNull() ?: 0L },
                        delays.getOrElse(index) { delays.lastOrNull() ?: 0L },
                        easings.getOrElse(index) { easings.lastOrNull() ?: "ease" },
                    )
                }.associateBy(InkTransitionSpec::property),
            )
        }

        fun isMotionProperty(property: String): Boolean =
            property == "opacity" || property == "transform" || property in GEOMETRY_PROPERTIES

        private fun parseShorthand(value: String): List<InkTransitionSpec> = splitCommas(value).mapNotNull { item ->
            val parts = item.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            val property = parts.firstOrNull() ?: return@mapNotNull null
            val times = parts.filter { it.endsWith("ms", true) || it.endsWith("s", true) }.map(::parseTime)
            val easing = parts.firstOrNull { it in EASINGS || it.startsWith("cubic-bezier(") } ?: "ease"
            InkTransitionSpec(property, times.getOrElse(0) { 0L }, times.getOrElse(1) { 0L }, easing)
        }

        private fun parseTime(value: String): Long = when {
            value.endsWith("ms", true) -> value.dropLast(2).toDoubleOrNull()?.toLong()
            value.endsWith("s", true) -> value.dropLast(1).toDoubleOrNull()?.times(1_000.0)?.toLong()
            else -> null
        }?.coerceIn(0L, MAX_TRANSITION_MS) ?: 0L

        private fun splitCommas(value: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = 0
            value.forEachIndexed { index, char ->
                when (char) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        result += value.substring(start, index)
                        start = index + 1
                    }
                }
            }
            result += value.substring(start)
            return result
        }

        private val EASINGS = setOf("linear", "ease", "ease-in", "ease-out", "ease-in-out")
        private val GEOMETRY_PROPERTIES = buildSet {
            addAll(setOf("width", "height"))
            listOf("top", "right", "bottom", "left").forEach(::add)
            listOf("margin", "padding").forEach { prefix ->
                add(prefix)
                listOf("top", "right", "bottom", "left").forEach { add("$prefix-$it") }
            }
        }
        private const val MAX_TRANSITION_MS = 5_000L
    }
}

internal data class InkTransformStyle(
    val translateX: String = "0px",
    val translateY: String = "0px",
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    companion object {
        fun parse(value: String?): InkTransformStyle {
            var result = InkTransformStyle()
            FUNCTION.findAll(value.orEmpty()).forEach { match ->
                val name = match.groupValues[1].lowercase()
                val args = match.groupValues[2].split(Regex("[,\\s]+")).filter(String::isNotBlank)
                result = when (name) {
                    "translate" -> result.copy(
                        translateX = args.getOrElse(0) { "0px" },
                        translateY = args.getOrElse(1) { "0px" },
                    )
                    "translatex" -> result.copy(translateX = args.getOrElse(0) { "0px" })
                    "translatey" -> result.copy(translateY = args.getOrElse(0) { "0px" })
                    "scale" -> {
                        val x = args.getOrNull(0)?.toFloatOrNull() ?: 1f
                        result.copy(scaleX = x, scaleY = args.getOrNull(1)?.toFloatOrNull() ?: x)
                    }
                    "scalex" -> result.copy(scaleX = args.getOrNull(0)?.toFloatOrNull() ?: 1f)
                    "scaley" -> result.copy(scaleY = args.getOrNull(0)?.toFloatOrNull() ?: 1f)
                    "rotate" -> result.copy(rotationDegrees = args.getOrNull(0)?.removeSuffix("deg")?.toFloatOrNull() ?: 0f)
                    else -> result
                }
            }
            return result
        }

        private val FUNCTION = Regex("([A-Za-z]+)\\(([^()]*)\\)")
    }
}
