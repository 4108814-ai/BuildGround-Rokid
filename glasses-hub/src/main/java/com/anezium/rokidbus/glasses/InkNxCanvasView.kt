package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.anezium.rokidbus.ink.RenderNode
import kotlin.math.PI
import kotlin.math.roundToInt

internal class InkNxCanvasView(
    context: Context,
    private val palette: InkColorPalette,
    private val frameGate: InkFrameGate,
) : View(context), InkAnimatedLeaf, InkFrameClient {
    private var program = InkCanvasProgram(listOf(emptyList()), InkNxCanvasLogic.MAX_FPS, loop = true)
    private var frameIndex = 0
    private var lastFrameNanos = 0L
    private var explicitlyVisible = true

    fun updateNode(node: RenderNode) {
        stopRecurring()
        program = InkNxCanvasLogic.parse(node.attributes)
            ?: InkCanvasProgram(listOf(emptyList()), InkNxCanvasLogic.MAX_FPS, loop = false)
        frameIndex = 0
        invalidate()
        updateRecurring()
        contentDescription = "Ink declarative canvas"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.black)
        val commands = program.frames.getOrNull(frameIndex).orEmpty()
        InkCanvasInterpreter(canvas, palette, resources.displayMetrics.density).draw(commands)
    }

    override fun onInkFrame(frameTimeNanos: Long): Boolean {
        if (!shouldRun()) return false
        val interval = 1_000_000_000L / program.fps
        if (lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < interval) return true
        lastFrameNanos = frameTimeNanos
        if (frameIndex == program.frames.lastIndex) {
            if (!program.loop) return false
            frameIndex = 0
        } else {
            frameIndex += 1
        }
        invalidate()
        return true
    }

    override fun onInkVisibilityChanged(visible: Boolean) {
        explicitlyVisible = visible
        updateRecurring()
    }

    override fun cancelInkAnimation() {
        stopRecurring()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRecurring()
    }

    override fun onDetachedFromWindow() {
        cancelInkAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRecurring()
    }

    private fun updateRecurring() {
        if (shouldRun()) frameGate.add(this) else stopRecurring()
    }

    private fun shouldRun(): Boolean =
        program.isSequence && explicitlyVisible && isAttachedToWindow && windowVisibility == VISIBLE

    private fun stopRecurring() {
        frameGate.remove(this)
        lastFrameNanos = 0L
    }
}

private class InkCanvasInterpreter(
    private val canvas: Canvas,
    private val palette: InkColorPalette,
    private val density: Float,
) {
    private data class GradientDefinition(
        val radial: Boolean,
        val values: List<Float>,
        val stops: MutableList<Pair<Float, Int>> = mutableListOf(),
    )

    private data class PaintState(val fill: Paint, val stroke: Paint)

    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.phosphor
        style = Paint.Style.FILL
        typeface = Typeface.MONOSPACE
        textSize = 15f * density
    }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val states = ArrayDeque<PaintState>()
    private val gradients = linkedMapOf<String, GradientDefinition>()

    fun draw(commands: List<InkCanvasCommand>) {
        commands.forEach(::execute)
        while (states.isNotEmpty()) {
            states.removeLast()
            canvas.restore()
        }
    }

    private fun execute(command: InkCanvasCommand) {
        val args = command.args
        when (command.name) {
            "fillStyle" -> applyStyle(fill, args.firstOrNull())
            "strokeStyle" -> applyStyle(stroke, args.firstOrNull())
            "lineWidth" -> stroke.strokeWidth = number(args, 0) * density
            "lineCap" -> stroke.strokeCap = when (args.firstOrNull()?.toString()) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            "lineJoin" -> stroke.strokeJoin = when (args.firstOrNull()?.toString()) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
            "lineDashOffset" -> Unit
            "globalAlpha" -> {
                val alpha = (number(args, 0).coerceIn(0f, 1f) * 255f).roundToInt()
                fill.alpha = alpha
                stroke.alpha = alpha
            }
            "font" -> applyFont(args.firstOrNull()?.toString().orEmpty())
            "textAlign" -> {
                val align = when (args.firstOrNull()?.toString()) {
                    "center" -> Paint.Align.CENTER
                    "right", "end" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                fill.textAlign = align
                stroke.textAlign = align
            }
            "textBaseline" -> Unit
            "fillRect" -> canvas.drawRect(number(args, 0), number(args, 1), number(args, 0) + number(args, 2), number(args, 1) + number(args, 3), fill)
            "strokeRect" -> canvas.drawRect(number(args, 0), number(args, 1), number(args, 0) + number(args, 2), number(args, 1) + number(args, 3), stroke)
            "clearRect" -> {
                val clear = Paint(fill).apply { color = palette.black; shader = null; alpha = 255 }
                canvas.drawRect(number(args, 0), number(args, 1), number(args, 0) + number(args, 2), number(args, 1) + number(args, 3), clear)
            }
            "beginPath" -> path.reset()
            "moveTo" -> path.moveTo(number(args, 0), number(args, 1))
            "lineTo" -> path.lineTo(number(args, 0), number(args, 1))
            "arc" -> arc(args)
            "rect" -> path.addRect(
                number(args, 0),
                number(args, 1),
                number(args, 0) + number(args, 2),
                number(args, 1) + number(args, 3),
                Path.Direction.CW,
            )
            "ellipse" -> ellipse(args)
            "arcTo" -> {
                // Android Path has no Canvas2D tangent-arc overload. A quadratic
                // through the first tangent point preserves the command's path
                // continuity and endpoint without inventing a second API name.
                path.quadTo(number(args, 0), number(args, 1), number(args, 2), number(args, 3))
            }
            "bezierCurveTo" -> path.cubicTo(
                number(args, 0), number(args, 1), number(args, 2), number(args, 3), number(args, 4), number(args, 5),
            )
            "quadraticCurveTo" -> path.quadTo(number(args, 0), number(args, 1), number(args, 2), number(args, 3))
            "closePath" -> path.close()
            "roundRect" -> {
                val rect = RectF(
                    number(args, 0),
                    number(args, 1),
                    number(args, 0) + number(args, 2),
                    number(args, 1) + number(args, 3),
                )
                path.addRoundRect(rect, number(args, 4), number(args, 4), Path.Direction.CW)
            }
            "clip" -> canvas.clipPath(path)
            "fill" -> canvas.drawPath(path, fill)
            "stroke" -> canvas.drawPath(path, stroke)
            "fillText" -> canvas.drawText(args.firstOrNull()?.toString().orEmpty(), number(args, 1), textBaseline(number(args, 2), fill), fill)
            "strokeText" -> canvas.drawText(args.firstOrNull()?.toString().orEmpty(), number(args, 1), textBaseline(number(args, 2), stroke), stroke)
            "save" -> {
                canvas.save()
                states.addLast(PaintState(Paint(fill), Paint(stroke)))
            }
            "restore" -> if (states.isNotEmpty()) {
                val state = states.removeLast()
                fill.set(state.fill)
                stroke.set(state.stroke)
                canvas.restore()
            }
            "translate" -> canvas.translate(number(args, 0), number(args, 1))
            "rotate" -> canvas.rotate((number(args, 0) * 180f / PI).toFloat())
            "scale" -> canvas.scale(number(args, 0), number(args, 1))
            "transform" -> canvas.concat(matrix(args))
            "setTransform" -> canvas.setMatrix(matrix(args))
            "resetTransform" -> canvas.setMatrix(Matrix())
            "setLineDash" -> {
                val dash = (args.firstOrNull() as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }.orEmpty()
                stroke.pathEffect = dash.takeIf(List<Float>::isNotEmpty)?.toFloatArray()?.let { DashPathEffect(it, 0f) }
            }
            "createLinearGradient" -> command.id?.let {
                gradients[it] = GradientDefinition(radial = false, values = args.mapIndexed { index, _ -> number(args, index) })
            }
            "createRadialGradient" -> command.id?.let {
                gradients[it] = GradientDefinition(radial = true, values = args.mapIndexed { index, _ -> number(args, index) })
            }
            "addColorStop" -> command.target?.let { target ->
                gradients[target]?.stops?.add(number(args, 0).coerceIn(0f, 1f) to resolveColor(args.getOrNull(1)?.toString()))
            }
        }
    }

    private fun applyStyle(paint: Paint, raw: Any?) {
        val reference = raw?.toString().orEmpty()
        val gradient = gradients[reference]
        if (gradient == null) {
            paint.shader = null
            paint.color = resolveColor(reference)
            return
        }
        val stops = gradient.stops.sortedBy(Pair<Float, Int>::first)
        val colors = stops.map(Pair<Float, Int>::second).ifEmpty { listOf(palette.dim, palette.phosphor) }.toIntArray()
        val positions = stops.map(Pair<Float, Int>::first).takeIf { it.size == colors.size }?.toFloatArray()
        paint.shader = if (gradient.radial) {
            RadialGradient(
                gradient.values.getOrElse(3) { 0f },
                gradient.values.getOrElse(4) { 0f },
                gradient.values.getOrElse(5) { 1f }.coerceAtLeast(0.01f),
                colors,
                positions,
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                gradient.values.getOrElse(0) { 0f },
                gradient.values.getOrElse(1) { 0f },
                gradient.values.getOrElse(2) { 0f },
                gradient.values.getOrElse(3) { 0f },
                colors,
                positions,
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun arc(args: List<Any?>) {
        val centerX = number(args, 0)
        val centerY = number(args, 1)
        val radius = number(args, 2)
        val start = number(args, 3) * 180f / PI.toFloat()
        val end = number(args, 4) * 180f / PI.toFloat()
        val anticlockwise = args.getOrNull(5) == true
        val sweep = if (anticlockwise) -((start - end) % 360f + 360f) % 360f else ((end - start) % 360f + 360f) % 360f
        path.arcTo(RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius), start, sweep, false)
    }

    private fun ellipse(args: List<Any?>) {
        val centerX = number(args, 0)
        val centerY = number(args, 1)
        val radiusX = number(args, 2)
        val radiusY = number(args, 3)
        val rotation = number(args, 4) * 180f / PI.toFloat()
        val oval = Path().apply {
            addOval(RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY), Path.Direction.CW)
            transform(Matrix().apply { setRotate(rotation, centerX, centerY) })
        }
        path.addPath(oval)
    }

    private fun matrix(args: List<Any?>): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                number(args, 0), number(args, 2), number(args, 4),
                number(args, 1), number(args, 3), number(args, 5),
                0f, 0f, 1f,
            ),
        )
    }

    private fun applyFont(value: String) {
        val size = Regex("([0-9]+(?:\\.[0-9]+)?)px").find(value)?.groupValues?.get(1)?.toFloatOrNull()
        if (size != null) {
            fill.textSize = size * density
            stroke.textSize = size * density
        }
        val bold = "bold" in value || Regex("[6-9]00").containsMatchIn(value)
        val typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        fill.typeface = typeface
        stroke.typeface = typeface
    }

    private fun textBaseline(y: Float, paint: Paint): Float = y - paint.ascent()

    private fun resolveColor(value: String?): Int = InkColorClamp.resolve(value, palette, InkColorTier.PHOSPHOR).color

    private fun number(args: List<Any?>, index: Int): Float = (args.getOrNull(index) as? Number)?.toFloat() ?: 0f
}
