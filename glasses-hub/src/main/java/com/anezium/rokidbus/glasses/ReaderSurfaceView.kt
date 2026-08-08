package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import kotlin.math.roundToInt

internal class ReaderSurfaceView(context: Context) : ScrollView(context) {
    private val document = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BusTheme.glassesBg)
    }
    private var renderedSurfaceId: String? = null
    private var renderGeneration = 0L
    private var pendingScrollLayoutListener: View.OnLayoutChangeListener? = null

    init {
        setBackgroundColor(BusTheme.glassesBg)
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true
        scrollBarSize = px(SCROLLBAR_WIDTH_DP)
        setVerticalScrollbarThumbDrawable(ColorDrawable(BusTheme.dim))
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = true
        addView(
            document,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun render(surfaceId: String, segments: List<ReaderSegment>) {
        invalidatePendingScrollRestore()
        val sameSurface = renderedSurfaceId == surfaceId
        val previousOffset = scrollY
        val wasNearBottom = sameSurface && maximumScroll() - previousOffset <= px(BOTTOM_PIN_SLOP_DP)
        renderedSurfaceId = surfaceId
        val generation = ++renderGeneration

        document.removeAllViews()
        segments.forEachIndexed { index, segment ->
            document.addView(
                segmentView(
                    segment = segment,
                    isFirst = index == 0,
                    followsProse = index > 0 && segments[index - 1].kind == ReaderSegmentKind.PROSE,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    when (segment.kind) {
                        ReaderSegmentKind.HEADER -> {
                            if (index > 0) topMargin = px(HEADER_TOP_MARGIN_DP)
                            bottomMargin = px(HEADER_BOTTOM_MARGIN_DP)
                        }
                        ReaderSegmentKind.ASIDE -> {
                            topMargin = px(ASIDE_MARGIN_DP)
                            bottomMargin = px(ASIDE_MARGIN_DP)
                        }
                        ReaderSegmentKind.PROSE -> Unit
                    }
                },
            )
        }

        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                document.removeOnLayoutChangeListener(this)
                if (pendingScrollLayoutListener === this) pendingScrollLayoutListener = null
                post {
                    if (generation != renderGeneration || renderedSurfaceId != surfaceId) return@post
                    val target = if (!sameSurface || wasNearBottom) {
                        maximumScroll()
                    } else {
                        previousOffset.coerceIn(0, maximumScroll())
                    }
                    scrollTo(0, target)
                }
            }
        }
        pendingScrollLayoutListener = listener
        document.addOnLayoutChangeListener(listener)
        document.requestLayout()
    }

    fun clear() {
        invalidatePendingScrollRestore()
        renderGeneration += 1
        renderedSurfaceId = null
        document.removeAllViews()
        scrollTo(0, 0)
    }

    fun smoothScrollByViewport(direction: Int) {
        if (direction == 0 || height <= 0) return
        val step = (height * VIEWPORT_SCROLL_FRACTION).roundToInt().coerceAtLeast(1)
        smoothScrollBy(0, step * direction.coerceIn(-1, 1))
    }

    override fun onDetachedFromWindow() {
        invalidatePendingScrollRestore()
        renderGeneration += 1
        super.onDetachedFromWindow()
    }

    private fun segmentView(
        segment: ReaderSegment,
        isFirst: Boolean,
        followsProse: Boolean,
    ): View = when (segment.kind) {
        ReaderSegmentKind.HEADER -> headerView(segment, decorated = !isFirst && followsProse)
        ReaderSegmentKind.PROSE -> proseView(segment.text)
        ReaderSegmentKind.ASIDE -> monoText(READER_SUB_SP, BusTheme.muted).apply {
            text = segment.text
        }
    }

    private fun headerView(segment: ReaderSegment, decorated: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BusTheme.glassesBg)
            if (decorated) {
                addView(
                    View(context).apply { setBackgroundColor(HAIRLINE_COLOR) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        bottomMargin = px(HAIRLINE_BOTTOM_MARGIN_DP)
                    },
                )
            }
            addView(
                monoText(READER_SUB_SP, BusTheme.muted).apply {
                    letterSpacing = HEADER_LETTER_SPACING_EM
                    text = styledHeader(segment)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun styledHeader(segment: ReaderSegment): CharSequence {
        val styled = SpannableString(segment.text)
        val separator = segment.text.indexOf('·')
        val tokenEnd = if (separator >= 0) separator else segment.text.length
        if (tokenEnd > 0) {
            styled.setSpan(
                ForegroundColorSpan(if (segment.emphasis) BusTheme.phosphor else BusTheme.text),
                0,
                tokenEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            styled.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                tokenEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return styled
    }

    private fun proseView(text: String): TextView = monoText(READER_BODY_SP, BusTheme.text).apply {
        this.text = text.ifEmpty { "\u00a0" }
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setLineSpacing(0f, PROSE_LINE_SPACING_MULTIPLIER)
    }

    private fun monoText(sizeSp: Float, color: Int): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        isSingleLine = false
        setHorizontallyScrolling(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    private fun maximumScroll(): Int = (document.height - height).coerceAtLeast(0)

    private fun invalidatePendingScrollRestore() {
        pendingScrollLayoutListener?.let(document::removeOnLayoutChangeListener)
        pendingScrollLayoutListener = null
    }

    private fun px(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private const val READER_SUB_SP = 12.5f
        private const val READER_BODY_SP = 14.5f
        private const val HEADER_LETTER_SPACING_EM = 0.03f
        private const val PROSE_LINE_SPACING_MULTIPLIER = 1.12f
        private const val VIEWPORT_SCROLL_FRACTION = 0.45f
        private const val HEADER_TOP_MARGIN_DP = 14
        private const val HEADER_BOTTOM_MARGIN_DP = 3
        private const val ASIDE_MARGIN_DP = 10
        private const val HAIRLINE_BOTTOM_MARGIN_DP = 14
        private const val BOTTOM_PIN_SLOP_DP = 24
        private const val SCROLLBAR_WIDTH_DP = 3
        private const val HAIRLINE_COLOR = 0x1F71FF97
    }
}
