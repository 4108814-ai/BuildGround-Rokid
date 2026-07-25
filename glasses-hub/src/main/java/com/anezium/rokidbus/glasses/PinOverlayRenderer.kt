package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.PinSurfacePosition

object PinOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: PinPanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unsubscribe: (() -> Unit)? = null

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        unsubscribe?.invoke()
        unsubscribe = PinController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        hide()
        this.service = null
        windowManager = null
    }

    fun ensureOnTop() {
        val manager = windowManager ?: return
        val currentRoot = root ?: return
        val currentParams = params ?: return
        runCatching {
            manager.removeView(currentRoot)
            manager.addView(currentRoot, currentParams)
        }.onFailure { logError("Pin overlay z-order refresh failed", it) }
    }

    private fun render(pin: NexusPinSurface?) {
        if (pin == null) {
            hide()
            return
        }
        val activeService = service ?: return
        val manager = windowManager
            ?: activeService.getSystemService(WindowManager::class.java)
            ?: return
        val currentRoot = root ?: PinPanelView(activeService).also { next ->
            val nextParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            applyPosition(nextParams, pin.content.position, activeService)
            if (runCatching { manager.addView(next, nextParams) }.isFailure) return
            root = next
            params = nextParams
        }
        currentRoot.render(pin)
        params?.let { layout ->
            applyPosition(layout, pin.content.position, activeService)
            runCatching { manager.updateViewLayout(currentRoot, layout) }
        }
    }

    private fun hide() {
        val currentRoot = root ?: return
        runCatching { windowManager?.removeView(currentRoot) }
        currentRoot.render(null)
        root = null
        params = null
    }

    private fun applyPosition(
        params: WindowManager.LayoutParams,
        position: PinSurfacePosition,
        context: Context,
    ) {
        params.gravity = when (position) {
            PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        params.x = BusTheme.dp(context, EDGE_MARGIN_DP)
        params.y = BusTheme.dp(context, EDGE_MARGIN_DP)
    }

    private class PinPanelView(context: Context) : LinearLayout(context) {
        private val title = row(13f, BusTheme.phosphor, bold = true)
        private val lines = List(2) { row(11f, BusTheme.muted) }

        init {
            orientation = VERTICAL
            val horizontalPadding = BusTheme.dp(context, 8)
            val verticalPadding = BusTheme.dp(context, 6)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Pure black: the additive AR optics emit nothing for black, so
                // the panel reads as transparent and only the border and text show.
                setColor(0xFF000000.toInt())
                setStroke(BusTheme.dp(context, 1), BusTheme.hairline)
                cornerRadius = BusTheme.dp(context, 7).toFloat()
            }
            addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            lines.forEachIndexed { index, line ->
                addView(
                    line,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = BusTheme.dp(context, if (index == 0) 3 else 1)
                    },
                )
            }
        }

        fun render(pin: NexusPinSurface?) {
            val content = pin?.content
            title.text = content?.title.orEmpty()
            title.visibility = visibleIf(!content?.title.isNullOrEmpty())
            lines.forEachIndexed { index, view ->
                val text = content?.lines?.getOrNull(index).orEmpty()
                view.text = text
                view.visibility = visibleIf(text.isNotEmpty())
            }
        }

        private fun row(sizeSp: Float, color: Int, bold: Boolean = false) =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * MAX_SCREEN_WIDTH_FRACTION).toInt()
            }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private const val EDGE_MARGIN_DP = 12
    private const val MAX_SCREEN_WIDTH_FRACTION = 0.45f
}
