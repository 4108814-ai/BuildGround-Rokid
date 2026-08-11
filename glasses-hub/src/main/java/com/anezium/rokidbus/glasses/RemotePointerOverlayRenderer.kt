package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

internal data class GlassesPointerPosition(val x: Double, val y: Double)

internal data class GlassesPointerPixel(val x: Float, val y: Float)

internal object RemotePointerGeometry {
    fun toPixels(
        position: GlassesPointerPosition,
        widthPixels: Int,
        heightPixels: Int,
        radiusPixels: Float,
    ): GlassesPointerPixel? {
        if (widthPixels <= 0 || heightPixels <= 0 || radiusPixels < 0f || !radiusPixels.isFinite()) {
            return null
        }
        if (!position.x.isFinite() || !position.y.isFinite()) return null
        val halfWidth = widthPixels / 2f
        val halfHeight = heightPixels / 2f
        val horizontalInset = radiusPixels.coerceAtMost(halfWidth)
        val verticalInset = radiusPixels.coerceAtMost(halfHeight)
        return GlassesPointerPixel(
            x = (position.x.coerceIn(0.0, 1.0) * widthPixels)
                .toFloat()
                .coerceIn(horizontalInset, widthPixels - horizontalInset),
            y = (position.y.coerceIn(0.0, 1.0) * heightPixels)
                .toFloat()
                .coerceIn(verticalInset, heightPixels - verticalInset),
        )
    }
}

/** Transparent, non-touchable cursor window owned by the existing AccessibilityService. */
internal object RemotePointerOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: PointerView? = null
    private var params: WindowManager.LayoutParams? = null

    fun onServiceConnected(owner: AccessibilityService) {
        service = owner
        windowManager = owner.getSystemService(WindowManager::class.java)
    }

    fun onServiceDestroyed(owner: AccessibilityService) {
        if (service !== owner) return
        hide()
        service = null
        windowManager = null
    }

    fun show(position: GlassesPointerPosition): GlassesPointerPixel? {
        val owner = service ?: return null
        val manager = windowManager ?: owner.getSystemService(WindowManager::class.java) ?: return null
        val metrics = owner.resources.displayMetrics
        val radius = dp(metrics.density, CURSOR_RADIUS_DP).toFloat()
        val point = RemotePointerGeometry.toPixels(
            position = position,
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            radiusPixels = radius,
        ) ?: return null
        val currentRoot = root ?: PointerView(owner).also { next ->
            val layout = pointerParams()
            if (
                runCatching { manager.addView(next, layout) }
                    .onFailure { logError("Pointer overlay window could not be added", it) }
                    .isFailure
            ) {
                return null
            }
            root = next
            params = layout
        }
        currentRoot.render(point, radius)
        return point
    }

    fun hide() {
        val currentRoot = root ?: return
        runCatching { windowManager?.removeView(currentRoot) }
            .onFailure { logError("Pointer overlay removal failed", it) }
        root = null
        params = null
    }

    /** Pointer is the final HUD layer so its click position remains visible over every surface. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val currentRoot = root ?: return
        val layout = params ?: return
        runCatching {
            manager.removeView(currentRoot)
            manager.addView(currentRoot, layout)
        }.onFailure { logError("Pointer overlay z-order refresh failed", it) }
    }

    private fun pointerParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private class PointerView(context: Context) : View(context) {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = POINTER_GREEN
            style = Paint.Style.FILL
        }
        private var point = GlassesPointerPixel(0f, 0f)
        private var radius = 0f

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun render(nextPoint: GlassesPointerPixel, nextRadius: Float) {
            point = nextPoint
            radius = nextRadius
            val density = resources.displayMetrics.density
            shadow.strokeWidth = dp(density, SHADOW_STROKE_DP).toFloat()
            ring.strokeWidth = dp(density, RING_STROKE_DP).toFloat()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(point.x, point.y, radius, shadow)
            canvas.drawCircle(point.x, point.y, radius, ring)
            canvas.drawCircle(point.x, point.y, radius * CENTER_RADIUS_RATIO, center)
        }
    }

    private fun dp(density: Float, value: Float): Int = (value * density).roundToInt().coerceAtLeast(1)

    private const val CURSOR_RADIUS_DP = 11f
    private const val SHADOW_STROKE_DP = 6f
    private const val RING_STROKE_DP = 2.5f
    private const val CENTER_RADIUS_RATIO = 0.24f
    private const val POINTER_GREEN = 0xff00e676.toInt()
}
