package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
        val view = NoticeBandView(service, NoticeController::setPageCount)
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
    internal class NoticeBandView(
        context: Context,
        private val pageCountChanged: ((String, Long, Int) -> Unit)? = null,
    ) : LinearLayout(context) {
        private val title = row(bold = true, sizeSp = TITLE_SP, color = BusTheme.phosphor)
        private val image = NoticeImageView(context)
        private val body = NoticeBodyView(context) { count ->
            measuredPageCount = count
            updateFooter()
            noticeIdentity?.let { (surfaceId, seq) ->
                pageCountChanged?.invoke(surfaceId, seq, count)
            }
        }
        private val footer = row(bold = false, sizeSp = FOOTER_SP, color = BusTheme.muted)
        private val pageIndicator = row(
            bold = false,
            sizeSp = FOOTER_SP,
            color = BusTheme.muted,
        ).apply {
            gravity = Gravity.END
        }
        private val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                footer,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                pageIndicator,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        private val actions = HudActionRowView(context)
        private var noticeIdentity: Pair<String, Long>? = null
        private var pluginFooter: String? = null
        private var renderedPageIndex = 0
        private var measuredPageCount = 1

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
                image,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                body,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, 3)
                },
            )
            addView(
                footerRow,
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
            noticeIdentity = notice.surfaceId to notice.seq
            pluginFooter = notice.content.footer
            renderedPageIndex = notice.pageIndex
            measuredPageCount = notice.pageCount
            renderTitle(notice.content.title, null)
            val hasImage = notice.imageBitmap?.takeUnless { it.isRecycled } != null
            val drawsImage = hasImage && notice.pageIndex == 0
            image.render(notice.imageBitmap?.takeIf { drawsImage })
            body.render(
                text = noticeBodyText(notice.content),
                pageIndex = notice.pageIndex,
                firstPageLines = if (hasImage) IMAGE_PAGE_BODY_LINES else MAX_BODY_LINES,
                // The same test as NoticeState.isPaged: a row of two or more
                // needs the directions to choose along; anything less leaves
                // them free to turn pages.
                paging = notice.content.actions.size <= 1,
            )
            updateFooter()
            actions.render(
                notice.liveActions.map { HudActionChip(it.glyph, it.label) },
                notice.selectedActionIndex,
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
            noticeIdentity = null
            pluginFooter = footerText
            renderedPageIndex = 0
            measuredPageCount = 1
            renderTitle(titleText, leadingGlyph)
            image.render(null)
            body.render(
                text = bodyText,
                pageIndex = 0,
                firstPageLines = MAX_BODY_LINES,
                paging = false,
            )
            updateFooter()
            actions.render(actionChips, selectedActionIndex)
        }

        private fun renderTitle(titleText: String?, leadingGlyph: Drawable?) {
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
        }

        private fun updateFooter() {
            footer.text = pluginFooter.orEmpty()
            footer.visibility = visibleIf(!pluginFooter.isNullOrEmpty())
            pageIndicator.text = if (measuredPageCount > 1) {
                "${renderedPageIndex.coerceIn(0, measuredPageCount - 1) + 1}/$measuredPageCount"
            } else {
                ""
            }
            pageIndicator.visibility = visibleIf(measuredPageCount > 1)
            footerRow.visibility = visibleIf(
                !pluginFooter.isNullOrEmpty() || measuredPageCount > 1,
            )
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

    private class NoticeImageView(context: Context) : ImageView(context) {
        init {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = MAX_IMAGE_HEIGHT_PX
            setBackgroundColor(0xFF000000.toInt())
        }

        fun render(bitmap: android.graphics.Bitmap?) {
            setImageBitmap(bitmap)
            visibility = if (bitmap == null) View.GONE else View.VISIBLE
        }
    }

    private class NoticeBodyView(
        context: Context,
        private val pageCountChanged: (Int) -> Unit,
    ) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BusTheme.muted
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                BODY_SP,
                resources.displayMetrics,
            )
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        private var text: String? = null
        private var pageIndex = 0
        private var firstPageLines = MAX_BODY_LINES
        private var paging = false
        private var layout: StaticLayout? = null
        private var window = NoticePageWindow(0, 0)
        private var reportedPageCount = 1

        fun render(
            text: String?,
            pageIndex: Int,
            firstPageLines: Int,
            paging: Boolean,
        ) {
            this.text = text
            this.pageIndex = pageIndex
            this.firstPageLines = firstPageLines
            this.paging = paging
            reportedPageCount = -1
            contentDescription = text.orEmpty()
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val content = text
            if (content.isNullOrEmpty()) {
                layout = null
                window = NoticePageWindow(0, 0)
                publishPageCount(1)
                setMeasuredDimension(width, 0)
                return
            }

            val builder = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (!paging) {
                builder
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(width)
                    .setMaxLines(firstPageLines)
            }
            val measured = builder.build()
            layout = measured
            val count = if (paging) {
                noticePageCount(measured.lineCount, firstPageLines, MAX_BODY_LINES)
            } else {
                1
            }
            publishPageCount(count)
            window = if (paging) {
                noticePageWindow(
                    pageIndex = pageIndex,
                    lineCount = measured.lineCount,
                    firstPageLines = firstPageLines,
                    followingPageLines = MAX_BODY_LINES,
                )
            } else {
                NoticePageWindow(0, measured.lineCount)
            }
            val desiredHeight = measured.getLineTop(window.lastLineExclusive) -
                measured.getLineTop(window.firstLine)
            setMeasuredDimension(
                width,
                resolveSize(desiredHeight, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val measured = layout ?: return
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(0f, -measured.getLineTop(window.firstLine).toFloat())
            measured.draw(canvas)
            canvas.restore()
        }

        private fun publishPageCount(count: Int) {
            if (reportedPageCount == count) return
            reportedPageCount = count
            pageCountChanged(count)
        }
    }

    private const val EDGE_MARGIN_DP = 12
    private const val BAND_WIDTH_FRACTION = 0.92f

    // Sized against the wire rather than chosen for looks. At this width the
    // optics carry ~34 monospace columns, so a page of eight lines holds more
    // than the 240 characters a single-page notice can carry, and eight lines
    // under a title and over a footer and an action row measure ~62% of the
    // screen. Anything smaller ellipsized valid notices by construction: the
    // band used to be a third shorter than what a plugin was allowed to send.
    //
    // A first-page image spends five of the eight; later pages recover the full
    // reading window instead of paying for the picture on every one.
    private const val MAX_HEIGHT_FRACTION = 0.65f
    private const val MAX_BODY_LINES = 8
    private const val IMAGE_PAGE_BODY_LINES = 3
    private const val MAX_IMAGE_HEIGHT_PX = 150
    private const val TITLE_SP = 15f
    private const val BODY_SP = 12f
    private const val FOOTER_SP = 11f
    private const val GLYPH_SIZE_DP = 36
}
