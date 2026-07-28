package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusGlyphs
import com.anezium.rokidbus.shared.ActivityProgress
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize

/**
 * The activity tier's one fixed full-screen window.
 *
 * Corner placement and every frame of pulse/flare motion happen on child
 * views. The WindowManager layout is never animated or updated.
 */
internal object ActivityOverlayRenderer {
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var flareBand: NoticeOverlayRenderer.NoticeBandView? = null
    private var unsubscribe: (() -> Unit)? = null
    private val nodes = linkedMapOf<String, ActivityNode>()
    private val processedMotionTokens = mutableMapOf<String, Long>()
    private var flareGeneration = 0L
    private var flareCollapse: Runnable? = null
    private var activeFlareId: String? = null
    private var latestState = ActivityRenderState()

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        unsubscribe?.invoke()
        unsubscribe = ActivityController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        teardown()
        processedMotionTokens.clear()
        this.service = null
        windowManager = null
    }

    /** Ambient stack order is pin, activity, notice. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val currentRoot = root ?: return
        runCatching {
            manager.removeView(currentRoot)
            manager.addView(currentRoot, params())
        }.onFailure { logError("Activity overlay z-order refresh failed", it) }
    }

    private fun render(state: ActivityRenderState) {
        latestState = state
        val liveIds = state.items.mapTo(mutableSetOf()) { it.activity.surfaceId }
        processedMotionTokens.keys.retainAll(liveIds)
        if (state.items.isEmpty()) {
            teardown()
            return
        }

        // Hidden updates still consume their motion token. Restoring after the
        // camera therefore restores current state without replaying a flare.
        state.items
            .filter { it.presentation == ActivityPresentation.HIDDEN }
            .forEach { processedMotionTokens[it.activity.surfaceId] = it.activity.motionToken }

        val visible = state.items.filter { it.presentation != ActivityPresentation.HIDDEN }
        if (visible.isEmpty()) {
            teardown(keepMotionTokens = true)
            return
        }
        val activeService = service ?: return
        val container = ensureWindow(activeService) ?: return
        activeFlareId?.let { surfaceId ->
            // A flare is a timed presentation event, not a property every
            // subsequent state publish repeats. Keep it running across content,
            // pin, and context publishes; only removal/hiding interrupts it.
            val stillCurrent = visible.any { it.activity.surfaceId == surfaceId }
            if (!stillCurrent) cancelFlare()
        }

        nodes.keys.filterNot(liveIds::contains).forEach { id ->
            nodes.remove(id)?.let(container::removeView)
        }

        var pendingFlare: Pair<ActivityRenderItem, ActivityNode>? = null
        visible.forEach { item ->
            val node = nodes[item.activity.surfaceId] ?: ActivityNode(activeService).also {
                nodes[item.activity.surfaceId] = it
                container.addView(it, cornerParams(activeService, item.activity.corner))
            }
            node.layoutParams = cornerParams(activeService, item.activity.corner)
            val previousToken = processedMotionTokens[item.activity.surfaceId] ?: Long.MIN_VALUE
            val newMotion = item.activity.motionToken > previousToken
            node.render(
                item = item,
                chipGlyph = GlassesHub.activityGlyphDrawable(
                    activeService,
                    item.activity.ownerPluginId,
                    item.activity.content.glyph,
                ),
                panelGlyph = GlassesHub.activityGlyphDrawable(
                    activeService,
                    item.activity.ownerPluginId,
                    item.activity.content.glyph,
                ),
            )
            if (item.activity.surfaceId == activeFlareId) node.showChip()
            when (item.presentation) {
                ActivityPresentation.PULSE -> {
                    if (newMotion) HudMotion.pulse(node.chip)
                }
                ActivityPresentation.FLARE -> {
                    if (newMotion) pendingFlare = item to node
                }
                ActivityPresentation.CHIP,
                ActivityPresentation.PANEL,
                ActivityPresentation.HIDDEN,
                -> Unit
            }
            if (newMotion) {
                processedMotionTokens[item.activity.surfaceId] = item.activity.motionToken
            }
        }
        pendingFlare?.let { (item, node) -> startFlare(item, node) }
    }

    private fun ensureWindow(service: AccessibilityService): FrameLayout? {
        root?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val nextRoot = FrameLayout(service)
        val nextBand = NoticeOverlayRenderer.NoticeBandView(service).apply {
            visibility = View.GONE
            alpha = 0f
        }
        nextRoot.addView(
            nextBand,
            FrameLayout.LayoutParams(
                (service.resources.displayMetrics.widthPixels * BAND_WIDTH_FRACTION).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(service, EDGE_MARGIN_DP)
            },
        )
        if (runCatching { manager.addView(nextRoot, params()) }.isFailure) {
            logError("Activity overlay window could not be added")
            return null
        }
        root = nextRoot
        flareBand = nextBand
        HudOverlayStack.reassert()
        return nextRoot
    }

    private fun startFlare(item: ActivityRenderItem, node: ActivityNode) {
        val band = flareBand ?: return
        cancelFlare()
        val generation = ++flareGeneration
        activeFlareId = item.activity.surfaceId
        node.showChip()
        val content = item.activity.content
        band.render(
            titleText = content.primary,
            bodyText = buildList {
                content.secondary?.let(::add)
                addAll(content.detail)
            }.joinToString("  •  ").takeIf(String::isNotEmpty),
            footerText = content.eta,
            leadingGlyph = GlassesHub.activityGlyphDrawable(
                band.context,
                item.activity.ownerPluginId,
                content.glyph,
            ),
        )
        band.visibility = View.VISIBLE
        band.alpha = 0f
        band.scaleX = 0.45f
        band.scaleY = 0.45f

        band.post {
            if (generation != flareGeneration || root == null) return@post
            val nodeLocation = IntArray(2).also(node::getLocationOnScreen)
            val bandLocation = IntArray(2).also(band::getLocationOnScreen)
            val anchorX = nodeLocation[0] + node.width / 2f
            val anchorY = nodeLocation[1] + node.height / 2f
            val targetX = bandLocation[0] + band.width / 2f
            val targetY = bandLocation[1] + band.height / 2f
            val translation = activityFlareTranslation(anchorX, anchorY, targetX, targetY)
            band.translationX = translation.bandToNodeX
            band.translationY = translation.bandToNodeY

            node.animate().cancel()
            band.animate().cancel()
            node.animate()
                .translationX(translation.nodeToBandX)
                .translationY(translation.nodeToBandY)
                .scaleX(1.12f)
                .scaleY(1.12f)
                .alpha(0f)
                .setDuration(HudMotion.STANDARD_MS)
                .setInterpolator(HudMotion.enter)
                .start()
            band.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(HudMotion.STANDARD_MS)
                .setInterpolator(HudMotion.enter)
                .withEndAction {
                    if (generation != flareGeneration) return@withEndAction
                    val collapse = Runnable {
                        collapseFlare(generation, node, band, translation)
                    }
                    flareCollapse = collapse
                    main.postDelayed(collapse, HudMotion.HOLD_MS)
                }
                .start()
        }
    }

    private fun collapseFlare(
        generation: Long,
        node: ActivityNode,
        band: NoticeOverlayRenderer.NoticeBandView,
        translation: ActivityFlareTranslation,
    ) {
        if (generation != flareGeneration) return
        flareCollapse = null
        node.visibility = View.VISIBLE
        node.showChip()
        node.animate().cancel()
        band.animate().cancel()
        node.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(HudMotion.EXIT_MS)
            .setInterpolator(HudMotion.exit)
            .start()
        band.animate()
            .translationX(translation.bandToNodeX)
            .translationY(translation.bandToNodeY)
            .scaleX(0.45f)
            .scaleY(0.45f)
            .alpha(0f)
            .setDuration(HudMotion.EXIT_MS)
            .setInterpolator(HudMotion.exit)
            .withEndAction {
                if (generation != flareGeneration) return@withEndAction
                band.visibility = View.GONE
                band.translationX = 0f
                band.translationY = 0f
                band.scaleX = 1f
                band.scaleY = 1f
                activeFlareId = null
                // Restore the platform-selected steady presentation (panel or
                // chip) after the mandated reverse collapse finishes.
                render(latestState)
            }
            .start()
    }

    private fun cancelFlare() {
        flareGeneration++
        activeFlareId = null
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        nodes.values.forEach { node ->
            node.animate().cancel()
            node.translationX = 0f
            node.translationY = 0f
            node.scaleX = 1f
            node.scaleY = 1f
            node.alpha = 1f
        }
        flareBand?.let { band ->
            band.animate().cancel()
            band.visibility = View.GONE
            band.alpha = 0f
            band.translationX = 0f
            band.translationY = 0f
            band.scaleX = 1f
            band.scaleY = 1f
        }
    }

    private fun teardown(keepMotionTokens: Boolean = false) {
        cancelFlare()
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
                .onFailure { logError("Activity overlay removal failed", it) }
        }
        root = null
        flareBand = null
        nodes.clear()
        if (!keepMotionTokens) processedMotionTokens.clear()
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun cornerParams(
        context: Context,
        corner: PinSurfacePosition,
    ) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = when (corner) {
            PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        val edge = dp(context, EDGE_MARGIN_DP)
        setMargins(edge, edge, edge, edge)
    }

    private class ActivityNode(context: Context) : FrameLayout(context) {
        val chip = PinOverlayRenderer.PinPanelView(context)
        private val panel = ActivityPanelView(context)

        init {
            addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                panel,
                LayoutParams(
                    (resources.displayMetrics.widthPixels * PANEL_WIDTH_FRACTION).toInt(),
                    LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun render(
            item: ActivityRenderItem,
            chipGlyph: Drawable,
            panelGlyph: Drawable,
        ) {
            val content = item.activity.content
            chip.render(
                titleText = content.primary,
                lineContent = content.secondary
                    ?.let { listOf(PinSurfaceLine(it)) }
                    .orEmpty(),
                size = PinSurfaceSize.MEDIUM,
                leadingGlyph = chipGlyph,
            )
            panel.render(
                content = content,
                mainGlyph = panelGlyph,
                selectedActionIndex = item.activity.selectedActionIndex,
            )
            visibility = if (item.presentation == ActivityPresentation.HIDDEN) {
                View.GONE
            } else {
                View.VISIBLE
            }
            when (item.presentation) {
                ActivityPresentation.PANEL -> showPanel()
                ActivityPresentation.CHIP,
                ActivityPresentation.FLARE,
                ActivityPresentation.PULSE,
                ActivityPresentation.HIDDEN,
                -> showChip()
            }
        }

        fun showChip() {
            chip.visibility = View.VISIBLE
            panel.visibility = View.GONE
        }

        private fun showPanel() {
            chip.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
    }

    /** The platform-owned expanded geometry; no plugin field controls it. */
    private class ActivityPanelView(context: Context) : LinearLayout(context) {
        private val glyph = ImageView(context)
        private val primary = text(PRIMARY_SP, BusTheme.phosphor, bold = true)
        private val eta = text(ETA_SP, BusTheme.muted)
        private val secondary = text(SECONDARY_SP, BusTheme.muted)
        private val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            progressTintList = ColorStateList.valueOf(BusTheme.phosphor)
            progressBackgroundTintList = ColorStateList.valueOf(BusTheme.hairline)
            indeterminateTintList = ColorStateList.valueOf(BusTheme.phosphor)
            max = 100
        }
        private val details = List(2) { text(DETAIL_SP, BusTheme.muted) }
        private val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            val horizontal = dp(context, 12)
            val vertical = dp(context, 10)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = panelBackground(context)

            addView(
                glyph,
                LayoutParams(dp(context, GLYPH_DP), dp(context, GLYPH_DP)).apply {
                    marginEnd = dp(context, 12)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(primary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                            addView(
                                eta,
                                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                                    .apply { marginStart = dp(context, 8) },
                            )
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        secondary,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, 2)
                        },
                    )
                    addView(
                        progress,
                        LayoutParams(LayoutParams.MATCH_PARENT, dp(context, PROGRESS_HEIGHT_DP)).apply {
                            topMargin = dp(context, 7)
                        },
                    )
                    details.forEach { detail ->
                        addView(
                            detail,
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(context, 4)
                            },
                        )
                    }
                    addView(
                        actions,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, 8)
                        },
                    )
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        fun render(
            content: ActivitySurfaceContent,
            mainGlyph: Drawable,
            selectedActionIndex: Int,
        ) {
            glyph.setImageDrawable(mainGlyph)
            primary.text = content.primary
            eta.text = content.eta.orEmpty()
            eta.visibility = visibleIf(content.eta != null)
            secondary.text = content.secondary.orEmpty()
            secondary.visibility = visibleIf(content.secondary != null)
            when (val value = content.progress) {
                null -> progress.visibility = View.GONE
                ActivityProgress.Indeterminate -> {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = true
                }
                is ActivityProgress.Percent -> {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = false
                    progress.progress = value.value
                }
            }
            details.forEachIndexed { index, view ->
                val line = content.detail.getOrNull(index)
                view.text = line.orEmpty()
                view.visibility = visibleIf(line != null)
            }
            actions.removeAllViews()
            content.actions.forEachIndexed { index, action ->
                actions.addView(
                    actionView(
                        context = context,
                        glyph = requireNotNull(
                            context.getDrawable(NexusGlyphs.drawableFor(action.glyph)),
                        ),
                        label = action.label,
                        selected = index == selectedActionIndex,
                    ),
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) marginStart = dp(context, 6)
                    },
                )
            }
            actions.visibility = visibleIf(content.actions.isNotEmpty())
        }

        private fun actionView(
            context: Context,
            glyph: Drawable,
            label: String,
            selected: Boolean,
        ) = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontal = dp(context, 6)
            val vertical = dp(context, 4)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(0xFF000000.toInt())
                setStroke(
                    dp(context, if (selected) 2 else 1),
                    if (selected) BusTheme.phosphor else BusTheme.hairline,
                )
                cornerRadius = dp(context, 5).toFloat()
            }
            addView(
                ImageView(context).apply { setImageDrawable(glyph) },
                LayoutParams(dp(context, ACTION_GLYPH_DP), dp(context, ACTION_GLYPH_DP)),
            )
            addView(
                text(ACTION_LABEL_SP, if (selected) BusTheme.phosphor else BusTheme.muted)
                    .apply { text = label },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(context, 4)
                },
            )
        }

        private fun text(sizeSp: Float, color: Int, bold: Boolean = false) =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    private fun panelBackground(context: Context) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xFF000000.toInt())
        setStroke(dp(context, 1), BusTheme.hairline)
        cornerRadius = dp(context, 7).toFloat()
    }

    private fun dp(context: Context, value: Int): Int = BusTheme.dp(context, value)

    private const val EDGE_MARGIN_DP = 12
    private const val BAND_WIDTH_FRACTION = 0.80f
    private const val PANEL_WIDTH_FRACTION = 0.78f
    private const val GLYPH_DP = 48
    private const val ACTION_GLYPH_DP = 18
    private const val PROGRESS_HEIGHT_DP = 4
    private const val PRIMARY_SP = 24f
    private const val SECONDARY_SP = 13f
    private const val ETA_SP = 13f
    private const val DETAIL_SP = 11f
    private const val ACTION_LABEL_SP = 10f
}
