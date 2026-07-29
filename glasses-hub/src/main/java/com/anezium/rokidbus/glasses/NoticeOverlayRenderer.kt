package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

/**
 * The notice band: a transient panel across the top that arrives, says its
 * piece, and leaves.
 *
 * The window is full-screen and the band is a child inside it. That is not
 * decoration: `updateViewLayout` is an IPC round-trip to `system_server`, so
 * driving it per frame races against the view's own frame production, and a
 * window can only translate and resize a rectangle where a view can also fade,
 * clip and morph. The window stays put and only child bounds move. See plan 013.
 *
 * Like the pin and like Relay's own overlay, the window is never focusable and
 * never touchable: it does not steal focus from what is underneath, and the
 * touchpad keeps working for everything the notice has not explicitly claimed.
 * It also never keeps the screen on and never wakes the display — a notice that
 * arrives on a dark screen is missed, and that is the correct behaviour.
 */
object NoticeOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var band: NoticeBandView? = null
    private var unsubscribe: (() -> Unit)? = null
    private var bandHeightPx = 0

    private val slide = HudMotionValue(0f) { offset -> band?.translationY = offset }
    private val fade = HudMotionValue(0f) { alpha -> band?.alpha = alpha }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        unsubscribe?.invoke()
        unsubscribe = NoticeController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        teardown()
        this.service = null
        windowManager = null
    }

    /** Re-add above the pin when the surface window is recreated; notice goes last. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val root = container ?: return
        runCatching {
            manager.removeView(root)
            manager.addView(root, params(root.context))
        }.onFailure { logError("Notice overlay z-order refresh failed", it) }
    }

    fun isShown(): Boolean = container != null

    private fun render(notice: NexusNoticeSurface?) {
        if (notice == null) {
            dismiss()
            return
        }
        val activeService = service ?: return
        val view = ensureWindow(activeService) ?: return
        val arriving = fade.current == 0f
        view.render(notice)
        if (arriving) {
            // Measure once the content is in place: the band's height is what the
            // arrival slides through, and it depends on how much body there is.
            view.post {
                bandHeightPx = view.height.takeIf { it > 0 } ?: bandHeightPx
                slide.snapTo(-bandHeightPx.toFloat())
                slide.animateTo(0f, HudMotion.STANDARD_MS, HudMotion.enter)
                fade.animateTo(1f, HudMotion.STANDARD_MS, HudMotion.enter)
            }
        }
    }

    private fun dismiss() {
        if (container == null) return
        slide.animateTo(-bandHeightPx.toFloat(), HudMotion.EXIT_MS, HudMotion.exit)
        fade.animateTo(0f, HudMotion.EXIT_MS, HudMotion.exit) { teardown() }
    }

    private fun ensureWindow(service: AccessibilityService): NoticeBandView? {
        band?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val root = FrameLayout(service)
        val view = NoticeBandView(service)
        val metrics = service.resources.displayMetrics
        root.addView(
            view,
            FrameLayout.LayoutParams(
                (metrics.widthPixels * BAND_WIDTH_FRACTION).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = BusTheme.dp(service, EDGE_MARGIN_DP)
            },
        )
        if (runCatching { manager.addView(root, params(service)) }.isFailure) {
            logError("Notice overlay window could not be added")
            return null
        }
        container = root
        band = view
        view.alpha = 0f
        fade.snapTo(0f)
        return view
    }

    private fun params(context: Context) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun teardown() {
        val root = container ?: return
        runCatching { windowManager?.removeView(root) }
            .onFailure { logError("Notice overlay removal failed", it) }
        container = null
        band = null
        slide.snapTo(0f)
        fade.snapTo(0f)
    }

    /** Shared top-band geometry used unchanged by notices and activity flares. */
    internal class NoticeBandView(context: Context) : LinearLayout(context) {
        private val title = row(bold = true, sizeSp = TITLE_SP, color = BusTheme.phosphor)
        private val body = row(bold = false, sizeSp = BODY_SP, color = BusTheme.muted).apply {
            isSingleLine = false
            maxLines = MAX_BODY_LINES
        }
        private val footer = row(bold = false, sizeSp = FOOTER_SP, color = BusTheme.muted)
        private val actions = HudActionRowView(context)

        init {
            orientation = VERTICAL
            val horizontal = BusTheme.dp(context, 10)
            val vertical = BusTheme.dp(context, 8)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Pure black. The additive optics emit nothing for black, so the
                // fill reads as transparent and only the border and text light up.
                // A "nicer" translucent grey is a visible grey rectangle on-glasses.
                setColor(0xFF000000.toInt())
                setStroke(BusTheme.dp(context, 1), BusTheme.hairline)
                cornerRadius = BusTheme.dp(context, 7).toFloat()
            }
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                body,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                footer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 5)
                },
            )
            // Under the footer, so the reading order is what the band says, then
            // how to answer it, then the answers themselves.
            addView(
                actions,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 6)
                },
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // The band grows with its body, then stops: past this it stops being a
            // glance and starts being a screen, which is a surface's job.
            val ceiling = (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST),
            )
        }

        /**
         * The band draws the notice's *live* actions, so an answered one loses
         * its row and becomes an inert display without the content it was shown
         * with being rewritten.
         */
        fun render(notice: NexusNoticeSurface) {
            render(
                titleText = notice.content.title,
                bodyText = notice.content.body,
                footerText = notice.content.footer,
                leadingGlyph = null,
                actionChips = notice.liveActions.map { HudActionChip(it.glyph, it.label) },
                selectedActionIndex = notice.selectedActionIndex,
            )
        }

        /**
         * The text-only form the activity flare borrows. It carries no actions:
         * a flare is a moment of emphasis on something the wearer is already
         * following, not a question, and its own row lives on the panel.
         */
        fun render(
            titleText: String?,
            bodyText: String?,
            footerText: String?,
            leadingGlyph: Drawable?,
            actionChips: List<HudActionChip> = emptyList(),
            selectedActionIndex: Int = 0,
        ) {
            title.text = titleText.orEmpty()
            title.visibility = visibleIf(!titleText.isNullOrEmpty())
            leadingGlyph?.setBounds(
                0,
                0,
                BusTheme.dp(context, GLYPH_SIZE_DP),
                BusTheme.dp(context, GLYPH_SIZE_DP),
            )
            title.compoundDrawablePadding = if (leadingGlyph == null) 0 else BusTheme.dp(context, 7)
            title.setCompoundDrawables(leadingGlyph, null, null, null)
            body.text = bodyText.orEmpty()
            body.visibility = visibleIf(!bodyText.isNullOrEmpty())
            footer.text = footerText.orEmpty()
            footer.visibility = visibleIf(!footerText.isNullOrEmpty())
            actions.render(actionChips, selectedActionIndex)
        }

        private fun row(bold: Boolean, sizeSp: Float, color: Int) =
            TextView(context).apply {
                setTextColor(color)
                textSize = sizeSp
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private const val EDGE_MARGIN_DP = 12
    private const val BAND_WIDTH_FRACTION = 0.92f

    // Sized so the longest body the wire accepts is drawn whole. At this width
    // the optics carry ~34 monospace columns, so the contract's 240 characters
    // land inside eight lines, and eight lines plus a title, a footer and an
    // action row measure ~62% of the screen. Anything smaller ellipsized valid
    // notices by construction: the band was a third shorter than what a plugin
    // was allowed to send it.
    private const val MAX_HEIGHT_FRACTION = 0.65f
    private const val MAX_BODY_LINES = 8
    private const val TITLE_SP = 15f
    private const val BODY_SP = 12f
    private const val FOOTER_SP = 11f
    private const val GLYPH_SIZE_DP = 36
}
