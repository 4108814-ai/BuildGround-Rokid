package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import kotlin.math.roundToInt

/**
 * A to-scale mock of the glasses panel with a draggable HUD band. The wearer
 * drags the band to where they keep their Hi Rokid screen; the band's top edge
 * is the hub-wide top inset every glasses surface starts from.
 */
internal class HudPositionPreviewView(context: Context) : View(context) {
    var insetDp: Int = PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP
        set(value) {
            field = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(value)
            invalidate()
        }

    var dragEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

    /** Fires on every drag tick with the draft value; cheap UI updates only. */
    var onInsetDragged: ((Int) -> Unit)? = null

    /** Fires once when the finger lifts; persist and announce here. */
    var onInsetCommitted: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val screenRect = RectF()
    private val bandRect = RectF()
    private val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.BG
        style = Paint.Style.FILL
    }
    private val screenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.LINE
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.alpha(NexusUi.GREEN_DIM, 26)
        strokeWidth = density
    }
    private val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.alpha(NexusUi.GREEN, 30)
        style = Paint.Style.FILL
    }
    private val bandStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.INK
        textAlign = Paint.Align.CENTER
        textSize = 13f * density * resources.configuration.fontScale
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.INK2
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density * resources.configuration.fontScale
        typeface = Typeface.MONOSPACE
    }

    private var scale = 1f
    private var dragging = false
    private var dragGripPx = 0f

    init {
        isClickable = true
        contentDescription = "Glasses display position"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(dp(200))
        val screenHeight = (availableWidth * PANEL_HEIGHT_PX / PANEL_WIDTH_PX).roundToInt()
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(paddingTop + screenHeight + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        scale = minOf(availableWidth / PANEL_WIDTH_PX, availableHeight / PANEL_HEIGHT_PX)
        val screenWidth = PANEL_WIDTH_PX * scale
        val screenHeight = PANEL_HEIGHT_PX * scale
        val left = paddingLeft + (availableWidth - screenWidth) / 2f
        val top = paddingTop + (availableHeight - screenHeight) / 2f
        screenRect.set(left, top, left + screenWidth, top + screenHeight)

        val corner = dp(10).toFloat()
        canvas.drawRoundRect(screenRect, corner, corner, screenFill)
        drawGrid(canvas)
        canvas.drawRoundRect(screenRect, corner, corner, screenStroke)

        val bandWidth = BAND_WIDTH_PX * scale
        val bandLeft = screenRect.centerX() - bandWidth / 2f
        val bandTop = screenRect.top + insetDp * PANEL_DENSITY * scale
        bandRect.set(bandLeft, bandTop, bandLeft + bandWidth, bandTop + BAND_HEIGHT_PX * scale)

        val bandCorner = dp(4).toFloat()
        canvas.drawRoundRect(bandRect, bandCorner, bandCorner, bandFill)
        canvas.drawRoundRect(bandRect, bandCorner, bandCorner, bandStroke)
        drawCentered(canvas, "Nexus HUD", bandRect.centerY() - dp(6), labelPaint)
        drawCentered(canvas, "$insetDp dp from top", bandRect.centerY() + dp(13), subPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!screenRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragGripPx = if (bandRect.contains(event.x, event.y)) {
                    event.y - bandRect.top
                } else {
                    bandRect.height() / 2f
                }
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onInsetCommitted?.invoke(insetDp)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(y: Float) {
        if (scale <= 0f) return
        val raw = ((y - screenRect.top - dragGripPx) / scale / PANEL_DENSITY).roundToInt()
        val clean = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(raw)
        if (clean == insetDp) return
        insetDp = clean
        onInsetDragged?.invoke(clean)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(12).toFloat()
        var x = screenRect.left + step
        while (x < screenRect.right) {
            canvas.drawLine(x, screenRect.top, x, screenRect.bottom, gridPaint)
            x += step
        }
        var y = screenRect.top + step
        while (y < screenRect.bottom) {
            canvas.drawLine(screenRect.left, y, screenRect.right, y, gridPaint)
            y += step
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, centerY: Float, paint: Paint) {
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, bandRect.centerX(), baseline, paint)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        // The glasses panel is 480x640 px at 2x density; the mock keeps its 3:4 shape.
        const val PANEL_WIDTH_PX = 480f
        const val PANEL_HEIGHT_PX = 640f
        const val BAND_WIDTH_PX = 442f
        const val BAND_HEIGHT_PX = 126f
        const val PANEL_DENSITY = 2f
    }
}
