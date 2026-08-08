package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.ink.InkActionBinding
import com.anezium.rokidbus.ink.RenderChange
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import com.anezium.rokidbus.ink.RenderPatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkRenderLogicTest {
    @Test
    fun `ink surface model preserves raw document and debug flags`() {
        val raw = """{"v":1,"doc":"doc","rev":0,"roots":[]}"""
        val surface = NexusSurface.fromPayload(
            JSONObject()
                .put("surfaceId", "ink")
                .put("seq", 1)
                .put("kind", NexusSurface.KIND_INK)
                .put(
                    "ink",
                    JSONObject()
                        .put("document", raw)
                        .put("debugActions", true)
                        .put("debugFrameMeter", true),
                ),
        )

        assertTrue(surface.isInk)
        assertEquals(raw, surface.ink!!.documentJson)
        assertTrue(surface.ink.debugActions)
        assertTrue(surface.ink.debugFrameMeter)
    }

    @Test
    fun `patch executor applies every structural and value change atomically`() {
        val store = store(
            RenderNode(
                "root",
                "view",
                children = listOf(
                    RenderNode("a", "#text", text = "A"),
                    RenderNode("b", "#text", text = "B", style = mapOf("opacity" to "1")),
                ),
            ),
        )
        val patch = patch(
            listOf(
                RenderChange.NodeMoved("b", "root", 1, 0),
                RenderChange.TextChanged("b", "Bee"),
                RenderChange.StyleChanged("b", "opacity", "0.5"),
                RenderChange.AttributeChanged("root", "id", "changed"),
                RenderChange.DatasetChanged("root", "row", 2),
                RenderChange.EventChanged("root", "tap", InkActionBinding("open", false)),
                RenderChange.NodeAdded("c", "root", 2, RenderNode("c", "#text", text = "C")),
                RenderChange.NodeRemoved("a", "root", 1),
            ),
        )

        val applied = InkNodeStore.Executor.apply(store, patch) as InkPatchApplyResult.Applied
        val next = applied.store

        assertEquals(1, next.revision)
        assertEquals(listOf("b", "c"), next.childIds("root"))
        assertEquals("Bee", next.node("b")!!.text)
        assertEquals("0.5", next.node("b")!!.style["opacity"])
        assertEquals("changed", next.node("root")!!.attributes["id"])
        assertEquals(2, next.node("root")!!.dataset["row"])
        assertEquals("open", next.node("root")!!.events["tap"]!!.actionId)
        assertNull(next.node("a"))
    }

    @Test
    fun `invalid later change leaves original store untouched`() {
        val store = store(RenderNode("root", "view", children = listOf(RenderNode("text", "#text", text = "old"))))
        val patch = patch(
            listOf(
                RenderChange.TextChanged("text", "new"),
                RenderChange.NodeRemoved("text", "root", 1),
            ),
        )

        assertTrue(InkNodeStore.Executor.apply(store, patch) is InkPatchApplyResult.Invalid)
        assertEquals("old", store.node("text")!!.text)
        assertEquals(0, store.revision)
    }

    @Test
    fun `document or base revision mismatch requests resync`() {
        val store = store(RenderNode("root", "view"))
        val wrongDocument = patch(emptyList()).copy(documentId = "other")
        val wrongRevision = patch(emptyList()).copy(baseRevision = 2, targetRevision = 3)

        assertTrue(InkNodeStore.Executor.apply(store, wrongDocument) is InkPatchApplyResult.ResyncNeeded)
        assertTrue(InkNodeStore.Executor.apply(store, wrongRevision) is InkPatchApplyResult.ResyncNeeded)
    }

    @Test
    fun `length resolver maps rpx percent and px against the right bases`() {
        assertEquals(240f, InkLengthResolver.resolve("375rpx", 300f, 480f, 1.5f)!!, 0.001f)
        assertEquals(75f, InkLengthResolver.resolve("25%", 300f, 480f, 1.5f)!!, 0.001f)
        assertEquals(15f, InkLengthResolver.resolve("10px", 300f, 480f, 1.5f)!!, 0.001f)
        assertEquals(-12f, InkLengthResolver.resolve("-8", 300f, 480f, 1.5f)!!, 0.001f)
        assertNull(InkLengthResolver.resolve("auto", 300f, 480f, 1.5f))
    }

    @Test
    fun `flex mapping resolves directions wrapping alignment and shorthand`() {
        val mapped = InkFlexStyle.from(
            mapOf(
                "flex-direction" to "column-reverse",
                "flex-wrap" to "wrap-reverse",
                "justify-content" to "space-between",
                "align-items" to "center",
                "align-self" to "flex-end",
                "flex" to "2 0 25%",
                "gap" to "8rpx",
            ),
        )

        assertEquals(InkFlexDirection.COLUMN_REVERSE, mapped.direction)
        assertEquals(InkFlexWrap.WRAP_REVERSE, mapped.wrap)
        assertEquals(InkJustify.SPACE_BETWEEN, mapped.justify)
        assertEquals(InkAlign.CENTER, mapped.alignItems)
        assertEquals(InkAlign.END, mapped.alignSelf)
        assertEquals(2f, mapped.grow)
        assertEquals(0f, mapped.shrink)
        assertEquals("25%", mapped.basis)
        assertEquals("8rpx", mapped.gap)
    }

    @Test
    fun `box shorthand follows CSS edge ordering and side overrides`() {
        val edges = InkBoxStyle.rawEdges(
            mapOf("padding" to "1px 2px 3px 4px", "padding-left" to "9px"),
            "padding",
        )
        assertEquals(InkBoxEdges("1px", "2px", "3px", "9px"), edges)
        assertEquals(
            InkBoxEdges("1px", "2px", "1px", "2px"),
            InkBoxStyle.rawEdges(mapOf("margin" to "1px 2px"), "margin"),
        )
    }

    @Test
    fun `literal colors clamp to a palette tier and tokens keep their tier`() {
        val palette = palette()
        val literal = InkColorClamp.resolve("#ff0000", palette, InkColorTier.TEXT)
        val token = InkColorClamp.resolve("var(--color-muted)", palette, InkColorTier.TEXT)

        assertTrue(literal.wasLiteral)
        assertTrue(literal.color in setOf(palette.phosphor, palette.muted, palette.dim, palette.danger))
        assertEquals(InkColorTier.DANGER, literal.tier)
        assertEquals(InkColorTier.MUTED, token.tier)
        assertEquals(palette.muted, token.color)
    }

    @Test
    fun `transition parser maps shorthand longhands and supported motion properties`() {
        val shorthand = InkTransitionTable.from(mapOf("transition" to "opacity 200ms ease-out 30ms, color 1s linear"))
        assertEquals(200L, shorthand.forProperty("opacity")!!.durationMs)
        assertEquals(30L, shorthand.forProperty("opacity")!!.delayMs)
        assertEquals("ease-out", shorthand.forProperty("opacity")!!.easing)
        assertEquals(1_000L, shorthand.forProperty("color")!!.durationMs)
        assertTrue(InkTransitionTable.isMotionProperty("opacity"))
        assertTrue(InkTransitionTable.isMotionProperty("width"))
        assertTrue(!InkTransitionTable.isMotionProperty("color"))

        val longhand = InkTransitionTable.from(
            mapOf(
                "transition-property" to "transform",
                "transition-duration" to "0.28s",
                "transition-delay" to "10ms",
            ),
        )
        assertEquals(280L, longhand.forProperty("transform")!!.durationMs)
        assertEquals(10L, longhand.forProperty("transform")!!.delayMs)
    }

    @Test
    fun `transform parser maps translation scale and rotation`() {
        val transform = InkTransformStyle.parse("translate(10rpx, 20%) scale(1.2, 0.8) rotate(12deg)")
        assertEquals("10rpx", transform.translateX)
        assertEquals("20%", transform.translateY)
        assertEquals(1.2f, transform.scaleX)
        assertEquals(0.8f, transform.scaleY)
        assertEquals(12f, transform.rotationDegrees)
    }

    private fun store(root: RenderNode): InkNodeStore = InkNodeStore.from(
        RenderDocument(listOf(root), documentId = "doc", revision = 0),
    )

    private fun patch(changes: List<RenderChange>): RenderPatch = RenderPatch(
        changes = changes,
        documentId = "doc",
        baseRevision = 0,
        targetRevision = 1,
    )

    private fun palette() = InkColorPalette(
        phosphor = 0xff71ff97.toInt(),
        text = 0xffecf4ec.toInt(),
        muted = 0xff7e9585.toInt(),
        dim = 0xff47584d.toInt(),
        danger = 0xffff7070.toInt(),
        black = 0xff000000.toInt(),
    )
}
