package com.anezium.rokidbus.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.anezium.rokidbus.client.ui.NexusUi
import kotlin.math.abs

/**
 * The phone half of the glasses pointer: a finger here, a cursor there.
 *
 * Deltas leave as fractions of this pad's own size, so the mapping stays honest
 * whatever the phone's screen is: crossing the pad crosses the glasses display.
 * The pad draws the finger back under itself because the cursor it drives is on
 * the other device — without that dot, a drag on a dark rectangle gives no sign
 * that anything was received.
 */
internal class TrackpadView(context: Context) : View(context) {
    var onMove: ((Float, Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NexusUi.GREEN }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NexusUi.alpha(NexusUi.GREEN, 0x33)
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false
    private var fingerX: Float? = null
    private var fingerY: Float? = null

    private val longPressRunnable = Runnable {
        if (!moved) {
            longPressFired = true
            onLongPress?.invoke()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        background = NexusUi.bordered(context, NexusUi.PANEL, NexusUi.LINE2, 15)
        contentDescription = context.getString(R.string.remote_input_trackpad_description)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                moved = false
                longPressFired = false
                setFinger(event.x, event.y)
                postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                setFinger(event.x, event.y)
                if (!moved &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
                if (moved && width > 0 && height > 0) {
                    onMove?.invoke(dx / width, dy / height)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                clearFinger()
                when {
                    longPressFired -> onGestureEnd?.invoke()
                    moved -> onGestureEnd?.invoke()
                    else -> onTap?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                clearFinger()
                onGestureEnd?.invoke()
            }
            else -> return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = fingerX ?: return
        val y = fingerY ?: return
        val radius = NexusUi.dp(context, 7).toFloat()
        canvas.drawCircle(x, y, radius * 2.4f, trailPaint)
        canvas.drawCircle(x, y, radius, dotPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private fun setFinger(x: Float, y: Float) {
        fingerX = x
        fingerY = y
        invalidate()
    }

    private fun clearFinger() {
        fingerX = null
        fingerY = null
        invalidate()
    }
}
