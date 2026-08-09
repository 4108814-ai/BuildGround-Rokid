package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.ink.RenderChange
import com.anezium.rokidbus.ink.RenderNode
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.AlignSelf
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import kotlin.math.roundToInt

/** Native View projection for one controller-owned Ink node store. */
internal class InkHudView(context: Context) : FrameLayout(context) {
    var onAction: ((String, Map<String, Any?>) -> Unit)? = null

    private data class Record(
        var node: RenderNode,
        var parentId: String?,
        val view: View,
        val childHost: FlexboxLayout? = null,
        val absoluteLayer: FrameLayout? = null,
        val scrollTarget: View? = null,
        val virtual: Boolean = false,
        val textOwnerId: String? = null,
    )

    private val palette = InkColorPalette(
        phosphor = BusTheme.phosphor,
        text = BusTheme.text,
        muted = BusTheme.muted,
        dim = BusTheme.dim,
        danger = BusTheme.danger,
        black = BusTheme.glassesBg,
    )
    private val rootFlex = FlexboxLayout(context).apply {
        flexDirection = FlexDirection.COLUMN
        flexWrap = FlexWrap.NOWRAP
        alignItems = AlignItems.STRETCH
    }
    private val rootAbsolute = FrameLayout(context)
    private val registry = linkedMapOf<String, Record>()
    private val motion = InkMotionAdapter()
    private val frameGate = InkFrameGate()
    private var store: InkNodeStore? = null
    private var projectedDocumentId: String? = null
    private var projectedRevision = -1
    private var containerWidth = 0
    private val layoutSettlePolicy = InkLayoutSettlePolicy()

    init {
        setBackgroundColor(BusTheme.glassesBg)
        clipChildren = false
        clipToPadding = false
        addView(rootFlex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rootAbsolute, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show(next: InkNodeStore, debugActions: Boolean) {
        if (projectedDocumentId == next.documentId && projectedRevision == next.revision && registry.isNotEmpty()) {
            registry.keys.toList().forEach(::refreshAction)
            return
        }
        clearProjection()
        store = next
        projectedDocumentId = next.documentId
        projectedRevision = next.revision
        next.rootNodes().forEachIndexed { index, node -> addSubtree(node, null, index) }
        layoutSettlePolicy.onProjectionChanged()
        requestLayout()
    }

    fun applyPatch(next: InkNodeStore, changes: List<RenderChange>, debugActions: Boolean) {
        if (projectedDocumentId != next.documentId || projectedRevision != next.revision - 1) {
            show(next, debugActions)
            return
        }
        store = next
        changes.forEach { change ->
            when (change) {
                is RenderChange.NodeAdded -> addSubtree(change.node, change.parentId, change.index)
                is RenderChange.NodeRemoved -> {
                    val parentId = registry[change.nodeId]?.parentId
                    removeSubtree(change.nodeId)
                    refreshTextOwner(parentId)
                }
                is RenderChange.NodeMoved -> moveNode(change.nodeId, change.parentId)
                is RenderChange.TextChanged -> refreshText(change.nodeId)
                is RenderChange.AttributeChanged -> refreshAttributes(change.nodeId)
                is RenderChange.StyleChanged -> refreshStyle(change.nodeId, change.name)
                is RenderChange.DatasetChanged,
                is RenderChange.EventChanged,
                -> refreshAction(change.nodeId)
            }
        }
        projectedRevision = next.revision
    }

    fun clearProjection() {
        motion.cancelAll()
        registry.values.forEach {
            it.view.animate().cancel()
            (it.view as? InkAnimatedLeaf)?.cancelInkAnimation()
        }
        frameGate.clear()
        rootFlex.removeAllViews()
        rootAbsolute.removeAllViews()
        registry.clear()
        store = null
        projectedDocumentId = null
        projectedRevision = -1
        layoutSettlePolicy.onProjectionChanged()
    }

    fun handleInkKeyEvent(event: KeyEvent): Boolean {
        val directional = event.keyCode in DIRECTION_KEYS
        if (directional) {
            val scroll = preferredScrollRecord() ?: return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                scrollByPage(scroll, forward)
            }
            return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        }
        if (
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode in CONFIRM_KEYS
        ) {
            val action = actionableRecord() ?: return false
            emitAction(action)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in CONFIRM_KEYS && actionableRecord() != null) {
            return true
        }
        return false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (
            layoutSettlePolicy.onPostLayout(width, height) ==
            InkLayoutSettleAction.REAPPLY_GEOMETRY
        ) {
            // Parent Flexboxes now have their stretched final bounds. Resolve
            // every percentage/rpx value against those bounds, then give the
            // tree one clean measure/layout before presentation is acknowledged.
            containerWidth = width
            registry.values.filterNot(Record::virtual).forEach { applyStyle(it) }
            rootFlex.requestLayout()
            rootAbsolute.requestLayout()
            requestLayout()
        }
    }

    internal fun isLayoutSettledForDraw(): Boolean =
        layoutSettlePolicy.canDraw(width, height)

    override fun onDetachedFromWindow() {
        clearProjection()
        super.onDetachedFromWindow()
    }

    private fun addSubtree(node: RenderNode, parentId: String?, logicalIndex: Int) {
        val textOwner = textOwnerFor(parentId)
        if (textOwner != null) {
            registerVirtualTree(node, parentId, textOwner)
            refreshTextOwner(textOwner)
            return
        }
        val record = createRecord(node, parentId)
        registry[node.id] = record
        addProjectedView(record, logicalIndex)
        applyStyle(record)
        refreshAction(node.id)
        when (node.type) {
            "text" -> node.children.forEach { registerVirtualTree(it, node.id, node.id) }
            "image", "chart", "lottie-view", "progress", "nx-canvas", "#text" -> Unit
            else -> node.children.forEachIndexed { index, child -> addSubtree(child, node.id, index) }
        }
        if (node.type == "text") refreshTextOwner(node.id)
    }

    private fun createRecord(node: RenderNode, parentId: String?): Record = when (node.type) {
        "view" -> InkFlexContainer(context).let { container ->
            Record(node, parentId, container, container.flex, container.absolute)
        }
        "scroll-view" -> InkScrollContainer(context, horizontal = scrollsHorizontally(node)).let { container ->
            Record(node, parentId, container, container.flex, container.absolute, container.scroller)
        }
        "image" -> InkImagePlaceholderView(context).apply {
            reference = node.attributes["src"]?.toString().orEmpty()
        }.let { Record(node, parentId, it) }
        "chart" -> InkChartView(context, palette).apply { updateNode(node) }
            .let { Record(node, parentId, it) }
        "lottie-view" -> InkLottieView(context, palette, frameGate).apply { updateNode(node) }
            .let { Record(node, parentId, it) }
        "progress" -> InkProgressView(context, palette).apply { updateNode(node) }
            .let { Record(node, parentId, it) }
        "nx-canvas" -> InkNxCanvasView(context, palette, frameGate).apply { updateNode(node) }
            .let { Record(node, parentId, it) }
        "text", "#text" -> monoHudText(context, DEFAULT_TEXT_SP, BusTheme.text).apply {
            text = node.text.orEmpty()
        }.let { Record(node, parentId, it) }
        else -> monoHudText(context, DEFAULT_TEXT_SP, BusTheme.danger).apply {
            text = "[UNSUPPORTED ${node.type}]"
        }.let { Record(node, parentId, it) }
    }

    private fun registerVirtualTree(node: RenderNode, parentId: String?, ownerId: String) {
        val owner = registry[ownerId] ?: return
        registry[node.id] = Record(
            node = node,
            parentId = parentId,
            view = owner.view,
            virtual = true,
            textOwnerId = ownerId,
        )
        node.children.forEach { registerVirtualTree(it, node.id, ownerId) }
    }

    private fun addProjectedView(record: Record, logicalIndex: Int) {
        val host = visualHost(record.node, record.parentId)
        val params = layoutParams(record, host)
        val visualIndex = visualIndex(host, record.parentId, logicalIndex)
        host.addView(record.view, visualIndex.coerceIn(0, host.childCount), params)
    }

    private fun moveNode(nodeId: String, parentId: String?) {
        val record = registry[nodeId] ?: return
        record.parentId = parentId
        if (record.virtual) {
            refreshTextOwner(parentId)
            return
        }
        (record.view.parent as? ViewGroup)?.removeView(record.view)
        val logicalIndex = store?.childIds(parentId)?.indexOf(nodeId)?.coerceAtLeast(0) ?: 0
        addProjectedView(record, logicalIndex)
        applyStyle(record)
    }

    private fun removeSubtree(nodeId: String) {
        val descendants = mutableListOf<String>()
        fun collect(id: String) {
            registry.values.filter { it.parentId == id }.forEach { collect(it.node.id) }
            descendants += id
        }
        collect(nodeId)
        val root = registry[nodeId]
        if (root?.virtual == false) (root.view.parent as? ViewGroup)?.removeView(root.view)
        descendants.forEach { id ->
            motion.cancelNode(id)
            registry.remove(id)?.view?.let { view ->
                view.animate().cancel()
                (view as? InkAnimatedLeaf)?.cancelInkAnimation()
            }
        }
    }

    private fun refreshText(nodeId: String) {
        val record = registry[nodeId] ?: return
        val next = store?.node(nodeId) ?: return
        record.node = next
        if (record.virtual) {
            refreshTextOwner(record.textOwnerId)
        } else {
            (record.view as? TextView)?.text = next.text.orEmpty()
        }
    }

    private fun refreshTextOwner(nodeId: String?) {
        val ownerId = textOwnerFor(nodeId) ?: return
        val record = registry[ownerId] ?: return
        val next = store?.node(ownerId) ?: return
        record.node = next
        (record.view as? TextView)?.text = next.renderedText()
    }

    private fun refreshAttributes(nodeId: String) {
        val record = registry[nodeId] ?: return
        val next = store?.node(nodeId) ?: return
        record.node = next
        if (record.virtual) {
            refreshTextOwner(record.textOwnerId)
            return
        }
        if (record.view is InkImagePlaceholderView) {
            record.view.reference = next.attributes["src"]?.toString().orEmpty()
        }
        when (val view = record.view) {
            is InkChartView -> view.updateNode(next)
            is InkLottieView -> view.updateNode(next)
            is InkProgressView -> view.updateNode(next)
            is InkNxCanvasView -> view.updateNode(next)
        }
        refreshAction(nodeId)
    }

    private fun refreshStyle(nodeId: String, property: String) {
        val record = registry[nodeId] ?: return
        val next = store?.node(nodeId) ?: return
        if (record.virtual) {
            record.node = next
            refreshTextOwner(record.textOwnerId)
            return
        }
        val oldNode = record.node
        record.node = next
        rehostIfNeeded(record, oldNode.style["position"], next.style["position"])
        val transition = InkTransitionTable.from(next.style).forProperty(property)
        if (transition != null && !InkTransitionTable.isMotionProperty(property)) {
            log("Ink transition '$property' is unsupported; snapping node=$nodeId")
            applyStyle(record)
            return
        }
        when (property) {
            "opacity" -> animateOpacity(record, transition)
            "transform" -> animateTransform(record, transition)
            in GEOMETRY_PROPERTIES -> animateGeometry(record, property, transition)
            else -> applyStyle(record)
        }
    }

    private fun refreshAction(nodeId: String) {
        val record = registry[nodeId] ?: return
        val next = store?.node(nodeId) ?: record.node
        record.node = next
        if (record.virtual) return
        val action = next.events["tap"]
        val actionEnabled = action != null
        record.view.isFocusable = actionEnabled
        record.view.isClickable = actionEnabled
        record.view.contentDescription = next.attributes["id"]?.toString()
            ?: action?.actionId
            ?: next.type
        record.view.setOnClickListener(
            if (actionEnabled) View.OnClickListener { emitAction(record) } else null,
        )
    }

    private fun applyStyle(record: Record, skip: Set<String> = emptySet()) {
        val view = record.view
        val style = record.node.style
        if (record.childHost != null) applyFlexStyle(record.childHost, style)
        applyLayoutStyle(record, skip)
        applyDecoration(view, style)
        if (view is TextView) applyTextStyle(view, style)
        val visible = style["display"] != "none"
        view.visibility = if (visible) VISIBLE else GONE
        (view as? InkAnimatedLeaf)?.onInkVisibilityChanged(visible)
        if (view is ViewGroup) {
            val clips = view is InkScrollContainer || style["overflow"] == "hidden"
            view.clipChildren = clips
            view.clipToPadding = clips
        }
        if ("opacity" !in skip) view.alpha = style["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        if ("transform" !in skip) applyTransform(view, InkTransformStyle.parse(style["transform"]))
    }

    private fun applyLayoutStyle(record: Record, skip: Set<String>) {
        val view = record.view
        val style = record.node.style
        val parent = view.parent as? ViewGroup
        val widthBase = parent?.width?.takeIf { it > 0 }?.toFloat() ?: inkWidth()
        val heightBase = parent?.height?.takeIf { it > 0 }?.toFloat() ?: height.toFloat().coerceAtLeast(1f)
        val params = view.layoutParams ?: return
        val replaced = view is InkChartView || view is InkLottieView || view is InkProgressView || view is InkNxCanvasView
        if ("width" !in skip) {
            params.width = style["width"]?.let { length(it, widthBase).roundToInt() }
                ?: if (replaced) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if ("height" !in skip) {
            params.height = style["height"]?.let { length(it, heightBase).roundToInt() }
                ?: if (replaced) defaultReplacedHeight(record) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (params is FlexboxLayout.LayoutParams) {
            val flex = InkFlexStyle.from(style)
            params.flexGrow = flex.grow
            params.flexShrink = flex.shrink
            params.alignSelf = flex.alignSelf.toAndroidAlignSelf()
            params.flexBasisPercent = -1f
            flex.basis?.let { basis ->
                if (basis.endsWith('%')) {
                    params.flexBasisPercent = (basis.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                } else {
                    val parentDirection = (parent as? FlexboxLayout)?.flexDirection
                    val pixels = length(basis, if (parentDirection.isColumn()) heightBase else widthBase).roundToInt()
                    if (parentDirection.isColumn()) params.height = pixels else params.width = pixels
                }
            }
        }
        if (params is ViewGroup.MarginLayoutParams && style["position"] != "absolute") {
            applyMargins(params, style, widthBase, skip)
        }
        if (params is FrameLayout.LayoutParams) applyAbsoluteInsets(params, style, widthBase, heightBase, skip)
        view.layoutParams = params
        val padding = InkBoxStyle.rawEdges(style, "padding")
        view.setPadding(
            if ("padding-left" in skip || "padding" in skip) view.paddingLeft else length(padding.left, widthBase).roundToInt(),
            if ("padding-top" in skip || "padding" in skip) view.paddingTop else length(padding.top, widthBase).roundToInt(),
            if ("padding-right" in skip || "padding" in skip) view.paddingRight else length(padding.right, widthBase).roundToInt(),
            if ("padding-bottom" in skip || "padding" in skip) view.paddingBottom else length(padding.bottom, widthBase).roundToInt(),
        )
        view.minimumWidth = style["min-width"]?.let { length(it, widthBase).roundToInt() }
            ?: if (view is InkImagePlaceholderView) px(96) else 0
        view.minimumHeight = style["min-height"]?.let { length(it, heightBase).roundToInt() }
            ?: if (view is InkImagePlaceholderView) px(64) else 0
        if (view is TextView) {
            view.maxWidth = style["max-width"]?.let { length(it, widthBase).roundToInt() } ?: Int.MAX_VALUE
            view.maxHeight = style["max-height"]?.let { length(it, heightBase).roundToInt() } ?: Int.MAX_VALUE
        }
    }

    private fun applyMargins(
        params: ViewGroup.MarginLayoutParams,
        style: Map<String, String>,
        widthBase: Float,
        skip: Set<String>,
    ) {
        val margin = InkBoxStyle.rawEdges(style, "margin")
        params.setMargins(
            if ("margin-left" in skip || "margin" in skip) params.leftMargin else length(margin.left, widthBase).roundToInt(),
            if ("margin-top" in skip || "margin" in skip) params.topMargin else length(margin.top, widthBase).roundToInt(),
            if ("margin-right" in skip || "margin" in skip) params.rightMargin else length(margin.right, widthBase).roundToInt(),
            if ("margin-bottom" in skip || "margin" in skip) params.bottomMargin else length(margin.bottom, widthBase).roundToInt(),
        )
    }

    private fun applyAbsoluteInsets(
        params: FrameLayout.LayoutParams,
        style: Map<String, String>,
        widthBase: Float,
        heightBase: Float,
        skip: Set<String>,
    ) {
        if (style["position"] != "absolute") return
        val inset = InkBoxStyle.rawEdges(style, "inset")
        val left = style["left"] ?: inset.left.takeIf { style.containsKey("inset") }
        val top = style["top"] ?: inset.top.takeIf { style.containsKey("inset") }
        val right = style["right"] ?: inset.right.takeIf { style.containsKey("inset") }
        val bottom = style["bottom"] ?: inset.bottom.takeIf { style.containsKey("inset") }
        if ("left" !in skip) params.leftMargin = left?.let { length(it, widthBase).roundToInt() } ?: 0
        if ("top" !in skip) params.topMargin = top?.let { length(it, heightBase).roundToInt() } ?: 0
        params.gravity = when {
            right != null && bottom != null -> Gravity.END or Gravity.BOTTOM
            right != null -> Gravity.END or Gravity.TOP
            bottom != null -> Gravity.START or Gravity.BOTTOM
            else -> Gravity.START or Gravity.TOP
        }
        if ("right" !in skip) params.rightMargin = right?.let { length(it, widthBase).roundToInt() } ?: 0
        if ("bottom" !in skip) params.bottomMargin = bottom?.let { length(it, heightBase).roundToInt() } ?: 0
    }

    private fun applyFlexStyle(view: FlexboxLayout, style: Map<String, String>) {
        val flex = InkFlexStyle.from(style)
        view.flexDirection = when (flex.direction) {
            InkFlexDirection.ROW -> FlexDirection.ROW
            InkFlexDirection.ROW_REVERSE -> FlexDirection.ROW_REVERSE
            InkFlexDirection.COLUMN -> FlexDirection.COLUMN
            InkFlexDirection.COLUMN_REVERSE -> FlexDirection.COLUMN_REVERSE
        }
        view.flexWrap = when (flex.wrap) {
            InkFlexWrap.NOWRAP -> FlexWrap.NOWRAP
            InkFlexWrap.WRAP -> FlexWrap.WRAP
            InkFlexWrap.WRAP_REVERSE -> FlexWrap.WRAP_REVERSE
        }
        view.justifyContent = when (flex.justify) {
            InkJustify.START -> JustifyContent.FLEX_START
            InkJustify.END -> JustifyContent.FLEX_END
            InkJustify.CENTER -> JustifyContent.CENTER
            InkJustify.SPACE_BETWEEN -> JustifyContent.SPACE_BETWEEN
            InkJustify.SPACE_AROUND -> JustifyContent.SPACE_AROUND
            InkJustify.SPACE_EVENLY -> JustifyContent.SPACE_EVENLY
        }
        view.alignItems = when (flex.alignItems) {
            InkAlign.START -> AlignItems.FLEX_START
            InkAlign.END -> AlignItems.FLEX_END
            InkAlign.CENTER -> AlignItems.CENTER
            InkAlign.BASELINE -> AlignItems.BASELINE
            InkAlign.AUTO,
            InkAlign.STRETCH,
            -> AlignItems.STRETCH
        }
        val gap = flex.gap?.let { length(it, inkWidth()).roundToInt().coerceAtLeast(0) } ?: 0
        if (gap > 0) {
            view.setDividerDrawable(InkGapDrawable(gap))
            view.setShowDivider(FlexboxLayout.SHOW_DIVIDER_MIDDLE)
        } else {
            view.setDividerDrawable(null)
            view.setShowDivider(FlexboxLayout.SHOW_DIVIDER_NONE)
        }
    }

    private fun applyDecoration(view: View, style: Map<String, String>) {
        if (view is InkImagePlaceholderView && style.keys.none { it.startsWith("border") || it == "background-color" }) {
            view.applyTokenFrame()
            return
        }
        val borderWidthValue = style["border-width"] ?: style["border"]?.split(Regex("\\s+"))?.firstOrNull()
        val borderWidth = borderWidthValue?.let { length(it, inkWidth()).roundToInt() } ?: 0
        val borderColor = InkColorClamp.resolve(style["border-color"], palette, InkColorTier.DIM).color
        val background = style["background-color"]?.let {
            InkColorClamp.resolve(it, palette, InkColorTier.BLACK).color
        } ?: Color.TRANSPARENT
        val radius = style["border-radius"]?.let { length(it, inkWidth()) } ?: 0f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(background)
            if (borderWidth > 0) setStroke(borderWidth, borderColor)
            cornerRadius = radius
        }
    }

    private fun applyTextStyle(view: TextView, style: Map<String, String>) {
        val color = InkColorClamp.resolve(style["color"], palette, InkColorTier.TEXT).color
        view.setTextColor(color)
        val sizePx = style["font-size"]?.let { length(it, inkWidth()) }
            ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP, resources.displayMetrics)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        val weight = style["font-weight"].orEmpty()
        val bold = weight == "bold" || (weight.toIntOrNull() ?: 0) >= 600
        view.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        view.gravity = when (style["text-align"]?.lowercase()) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right", "end" -> Gravity.END
            else -> Gravity.START
        }
        val nowrap = style["white-space"] == "nowrap"
        view.maxLines = if (nowrap) 1 else Int.MAX_VALUE
        view.ellipsize = if (style["text-overflow"] == "ellipsis") TextUtils.TruncateAt.END else null
        style["line-height"]?.let { lineHeight ->
            val linePx = length(lineHeight, inkWidth())
            view.setLineSpacing(0f, (linePx / sizePx).coerceAtLeast(0.5f))
        }
    }

    private fun animateOpacity(record: Record, transition: InkTransitionSpec?) {
        applyStyle(record, setOf("opacity"))
        val target = record.node.style["opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        motion.animate(record.node.id, "opacity", view = record.view, from = record.view.alpha, target = target, transition = transition) {
            record.view.alpha = it
        }
    }

    private fun animateTransform(record: Record, transition: InkTransitionSpec?) {
        applyStyle(record, setOf("transform"))
        val target = InkTransformStyle.parse(record.node.style["transform"])
        val view = record.view
        val targetX = length(target.translateX, view.width.toFloat().coerceAtLeast(inkWidth()))
        val targetY = length(target.translateY, view.height.toFloat().coerceAtLeast(height.toFloat()))
        listOf(
            TransformAnimation("translationX", view.translationX, targetX) { view.translationX = it },
            TransformAnimation("translationY", view.translationY, targetY) { view.translationY = it },
            TransformAnimation("scaleX", view.scaleX, target.scaleX) { view.scaleX = it },
            TransformAnimation("scaleY", view.scaleY, target.scaleY) { view.scaleY = it },
            TransformAnimation("rotation", view.rotation, target.rotationDegrees) { view.rotation = it },
        ).forEach { animation ->
            motion.animate(
                record.node.id,
                "transform",
                animation.component,
                view,
                animation.from,
                animation.target,
                transition,
                animation.apply,
            )
        }
    }

    private fun animateGeometry(record: Record, property: String, transition: InkTransitionSpec?) {
        val skip = geometrySkip(property)
        applyStyle(record, skip)
        when (property) {
            "width", "height" -> animateDimension(record, property, transition)
            "top", "right", "bottom", "left" -> animateInset(record, property, transition)
            "margin", "padding" -> SIDES.forEach { animateEdge(record, property, it, transition) }
            else -> {
                val prefix = property.substringBefore('-')
                val side = property.substringAfter('-', "")
                if (prefix in setOf("margin", "padding") && side in SIDES) {
                    animateEdge(record, prefix, side, transition)
                } else {
                    applyStyle(record)
                }
            }
        }
    }

    private fun animateDimension(record: Record, property: String, transition: InkTransitionSpec?) {
        val params = record.view.layoutParams ?: return
        val target = length(record.node.style[property], if (property == "width") inkWidth() else height.toFloat())
        val from = if (property == "width") record.view.width.toFloat() else record.view.height.toFloat()
        motion.animate(record.node.id, property, view = record.view, from = from, target = target, transition = transition) { value ->
            if (property == "width") params.width = value.roundToInt() else params.height = value.roundToInt()
            record.view.layoutParams = params
        }
    }

    private fun animateInset(record: Record, property: String, transition: InkTransitionSpec?) {
        val params = record.view.layoutParams as? FrameLayout.LayoutParams ?: return applyStyle(record)
        val vertical = property == "top" || property == "bottom"
        val target = length(record.node.style[property], if (vertical) height.toFloat() else inkWidth())
        val from = when (property) {
            "left" -> params.leftMargin
            "top" -> params.topMargin
            "right" -> params.rightMargin
            else -> params.bottomMargin
        }.toFloat()
        motion.animate(record.node.id, property, view = record.view, from = from, target = target, transition = transition) { value ->
            when (property) {
                "left" -> params.leftMargin = value.roundToInt()
                "top" -> params.topMargin = value.roundToInt()
                "right" -> params.rightMargin = value.roundToInt()
                "bottom" -> params.bottomMargin = value.roundToInt()
            }
            record.view.layoutParams = params
        }
    }

    private fun animateEdge(
        record: Record,
        prefix: String,
        side: String,
        transition: InkTransitionSpec?,
    ) {
        val edges = InkBoxStyle.rawEdges(record.node.style, prefix)
        val raw = when (side) {
            "left" -> edges.left
            "top" -> edges.top
            "right" -> edges.right
            else -> edges.bottom
        }
        val target = length(raw, inkWidth())
        val from = if (prefix == "padding") {
            when (side) {
                "left" -> record.view.paddingLeft
                "top" -> record.view.paddingTop
                "right" -> record.view.paddingRight
                else -> record.view.paddingBottom
            }
        } else {
            val params = record.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            when (side) {
                "left" -> params.leftMargin
                "top" -> params.topMargin
                "right" -> params.rightMargin
                else -> params.bottomMargin
            }
        }.toFloat()
        motion.animate(
            record.node.id,
            "$prefix-$side",
            view = record.view,
            from = from,
            target = target,
            transition = transition,
        ) { value ->
            if (prefix == "padding") {
                val left = if (side == "left") value.roundToInt() else record.view.paddingLeft
                val top = if (side == "top") value.roundToInt() else record.view.paddingTop
                val right = if (side == "right") value.roundToInt() else record.view.paddingRight
                val bottom = if (side == "bottom") value.roundToInt() else record.view.paddingBottom
                record.view.setPadding(left, top, right, bottom)
            } else {
                val params = record.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@animate
                when (side) {
                    "left" -> params.leftMargin = value.roundToInt()
                    "top" -> params.topMargin = value.roundToInt()
                    "right" -> params.rightMargin = value.roundToInt()
                    "bottom" -> params.bottomMargin = value.roundToInt()
                }
                record.view.layoutParams = params
            }
        }
    }

    private fun applyTransform(view: View, transform: InkTransformStyle) {
        view.translationX = length(transform.translateX, view.width.toFloat().coerceAtLeast(inkWidth()))
        view.translationY = length(transform.translateY, view.height.toFloat().coerceAtLeast(height.toFloat()))
        view.scaleX = transform.scaleX
        view.scaleY = transform.scaleY
        view.rotation = transform.rotationDegrees
    }

    private fun rehostIfNeeded(record: Record, oldPosition: String?, newPosition: String?) {
        val currentHost = record.view.parent as? ViewGroup
        val nextHost = visualHost(record.node, record.parentId)
        if (currentHost === nextHost && oldPosition == newPosition) return
        currentHost?.removeView(record.view)
        val logicalIndex = store?.childIds(record.parentId)?.indexOf(record.node.id)?.coerceAtLeast(0) ?: 0
        addProjectedView(record, logicalIndex)
    }

    private fun visualHost(node: RenderNode, parentId: String?): ViewGroup {
        if (node.style["position"] == "absolute") {
            var ancestorId = parentId
            while (ancestorId != null) {
                val ancestor = registry[ancestorId]
                if (ancestor?.node?.style?.get("position") == "relative" && ancestor.absoluteLayer != null) {
                    return ancestor.absoluteLayer
                }
                ancestorId = ancestor?.parentId
            }
            return rootAbsolute
        }
        return parentId?.let { registry[it]?.childHost } ?: rootFlex
    }

    private fun layoutParams(record: Record, host: ViewGroup): ViewGroup.LayoutParams =
        if (host is FlexboxLayout) {
            FlexboxLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                val flex = InkFlexStyle.from(record.node.style)
                flexGrow = flex.grow
                flexShrink = flex.shrink
                alignSelf = flex.alignSelf.toAndroidAlignSelf()
            }
        } else {
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

    private fun visualIndex(host: ViewGroup, parentId: String?, logicalIndex: Int): Int {
        val siblings = store?.childIds(parentId).orEmpty().take(logicalIndex)
        return siblings.count { siblingId ->
            val sibling = registry[siblingId]
            sibling != null && !sibling.virtual && sibling.view.parent === host
        }
    }

    private fun textOwnerFor(nodeId: String?): String? {
        var current = nodeId
        while (current != null) {
            val record = registry[current] ?: return null
            if (!record.virtual && record.node.type == "text") return record.node.id
            record.textOwnerId?.let { return it }
            current = record.parentId
        }
        return null
    }

    private fun preferredScrollRecord(): Record? {
        val scrollRecords = registry.values.filter { !it.virtual && it.scrollTarget != null }
        if (scrollRecords.isEmpty()) return null
        val focused = findFocus()
        if (focused != null) {
            scrollRecords.lastOrNull { it.view === focused || it.view.containsDescendant(focused) }?.let { return it }
        }
        return scrollRecords.firstOrNull { candidate ->
            var parentId = candidate.parentId
            var nested = false
            while (parentId != null) {
                val parent = registry[parentId]
                if (parent?.scrollTarget != null) nested = true
                parentId = parent?.parentId
            }
            !nested
        } ?: scrollRecords.first()
    }

    private fun scrollByPage(record: Record, forward: Boolean) {
        val direction = if (forward) 1 else -1
        val target = record.scrollTarget
        when (target) {
            is ScrollView -> target.scrollBy(0, direction * (target.height * SCROLL_PAGE_FRACTION).roundToInt().coerceAtLeast(px(48)))
            is HorizontalScrollView -> target.scrollBy(
                direction * (target.width * SCROLL_PAGE_FRACTION).roundToInt().coerceAtLeast(px(48)),
                0,
            )
        }
    }

    private fun actionableRecord(): Record? {
        val focused = findFocus()
        if (focused != null) registry.values.firstOrNull { !it.virtual && it.view === focused && "tap" in it.node.events }
            ?.let { return it }
        return registry.values.firstOrNull { !it.virtual && "tap" in it.node.events }
    }

    private fun emitAction(record: Record) {
        val action = record.node.events["tap"] ?: return
        onAction?.invoke(action.actionId, record.node.dataset)
    }

    private fun length(value: String?, percentBase: Float): Float = InkLengthResolver.resolve(
        value,
        percentBase.coerceAtLeast(1f),
        inkWidth(),
        resources.displayMetrics.density,
    ) ?: 0f

    private fun inkWidth(): Float = (containerWidth.takeIf { it > 0 } ?: width.takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels).toFloat().coerceAtLeast(1f)

    private fun scrollsHorizontally(node: RenderNode): Boolean =
        node.attributes["scroll-x"] == true && node.attributes["scroll-y"] != true

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun defaultReplacedHeight(record: Record): Int {
        val authored = (record.node.attributes["height"] as? Number)?.toFloat()
        if (authored != null) return (authored * resources.displayMetrics.density).roundToInt()
        return when (record.node.type) {
            "progress" -> px(if (record.node.attributes["show-info"] == true) 28 else 18)
            else -> px(128)
        }
    }

    private data class TransformAnimation(
        val component: String,
        val from: Float,
        val target: Float,
        val apply: (Float) -> Unit,
    )

    private companion object {
        const val DEFAULT_TEXT_SP = 15f
        const val SCROLL_PAGE_FRACTION = 0.75f
        val DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
        val SIDES = listOf("left", "top", "right", "bottom")
        val GEOMETRY_PROPERTIES = buildSet {
            addAll(setOf("width", "height", "top", "right", "bottom", "left", "margin", "padding"))
            listOf("margin", "padding").forEach { prefix -> SIDES.forEach { add("$prefix-$it") } }
        }

        fun geometrySkip(property: String): Set<String> = if (property == "margin" || property == "padding") {
            setOf(property) + SIDES.map { "$property-$it" }
        } else {
            setOf(property)
        }
    }
}

private class InkFlexContainer(context: Context) : FrameLayout(context) {
    val flex = FlexboxLayout(context)
    val absolute = FrameLayout(context)

    init {
        clipChildren = false
        addView(flex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(absolute, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

private class InkScrollContainer(context: Context, horizontal: Boolean) : FrameLayout(context) {
    val flex = FlexboxLayout(context)
    val absolute = FrameLayout(context)
    val scroller: View = if (horizontal) {
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(flex, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    } else {
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(flex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    init {
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(absolute, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

private class InkImagePlaceholderView(context: Context) : FrameLayout(context) {
    private val label = monoHudText(context, 11f, BusTheme.muted).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    var reference: String = ""
        set(value) {
            field = value
            label.text = if (value.isBlank()) "IMAGE" else "IMAGE\n$value"
            contentDescription = "Ink image $value"
        }

    init {
        minimumWidth = (96 * resources.displayMetrics.density).roundToInt()
        minimumHeight = (64 * resources.displayMetrics.density).roundToInt()
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyTokenFrame()
    }

    fun applyTokenFrame() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke((1 * resources.displayMetrics.density).roundToInt().coerceAtLeast(1), BusTheme.dim)
            cornerRadius = 4 * resources.displayMetrics.density
        }
    }
}

private class InkGapDrawable(private val gap: Int) : Drawable() {
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT
    override fun getIntrinsicWidth(): Int = gap
    override fun getIntrinsicHeight(): Int = gap
}

private fun RenderNode.renderedText(): String = buildString {
    text?.let(::append)
    children.forEach { append(it.renderedText()) }
}

private fun View.containsDescendant(target: View): Boolean {
    if (this !is ViewGroup) return false
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child === target || child.containsDescendant(target)) return true
    }
    return false
}

private fun InkAlign.toAndroidAlignSelf(): Int = when (this) {
    InkAlign.AUTO -> AlignSelf.AUTO
    InkAlign.START -> AlignSelf.FLEX_START
    InkAlign.END -> AlignSelf.FLEX_END
    InkAlign.CENTER -> AlignSelf.CENTER
    InkAlign.BASELINE -> AlignSelf.BASELINE
    InkAlign.STRETCH -> AlignSelf.STRETCH
}

private fun Int?.isColumn(): Boolean = this == FlexDirection.COLUMN || this == FlexDirection.COLUMN_REVERSE
