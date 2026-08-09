package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import com.anezium.rokidbus.client.ui.BusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InkCardPresentationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `card and notice share width top anchor and available height`() {
        val topPx = HudBandGeometry.topPx(context, hudTopInsetDp = 40)

        assertEquals(441, HudBandGeometry.widthPx(displayWidthPx = 480))
        assertEquals(BusTheme.dp(context, 52), topPx)
        assertEquals(640 - topPx, HudBandGeometry.availableHeightPx(640, topPx))
        assertEquals(0, HudBandGeometry.availableHeightPx(640, 700))
    }

    @Test
    fun `only Ink uses card geometry and it always uses the overlay path`() {
        assertEquals(SurfaceHudMode.INK_CARD, surfaceHudMode(NexusSurface.KIND_INK))
        listOf(
            NexusSurface.KIND_CARD,
            NexusSurface.KIND_READER,
            NexusSurface.KIND_TIMED_LINES,
            NexusSurface.KIND_MEDIA,
            NexusSurface.KIND_IMAGE,
        ).forEach { kind ->
            assertEquals(kind, SurfaceHudMode.FULL_BLEED, surfaceHudMode(kind))
        }

        assertEquals(
            SurfaceDisplayPath.OVERLAY,
            surfaceDisplayPath(surface(NexusSurface.KIND_INK), SurfaceDisplayPath.ACTIVITY),
        )
        assertEquals(
            SurfaceDisplayPath.ACTIVITY,
            surfaceDisplayPath(surface(NexusSurface.KIND_CARD), SurfaceDisplayPath.ACTIVITY),
        )
        assertEquals(
            SurfaceDisplayPath.OVERLAY,
            surfaceDisplayPath(surface(NexusSurface.KIND_READER), SurfaceDisplayPath.OVERLAY),
        )
    }

    @Test
    fun `same ID updates retain a pending deadline but a fresh rearmed show prepares again`() {
        assertTrue(
            shouldKeepInkPresentation(
                nextIsInk = true,
                nextSurfaceId = "assistant:ink",
                presentationSurfaceId = "assistant:ink",
                pendingGeneration = 4L,
                presentationGeneration = 4L,
            ),
        )
        assertFalse(
            shouldKeepInkPresentation(
                nextIsInk = true,
                nextSurfaceId = "assistant:ink",
                presentationSurfaceId = "assistant:ink",
                pendingGeneration = 5L,
                presentationGeneration = 4L,
            ),
        )
        assertTrue(
            shouldKeepInkPresentation(
                nextIsInk = true,
                nextSurfaceId = "assistant:ink",
                presentationSurfaceId = "assistant:ink",
                pendingGeneration = null,
                presentationGeneration = 4L,
            ),
        )
    }

    @Test
    fun `Ink removes host chrome and non Ink keeps the full bleed panel`() {
        val card = surfaceHostChrome(SurfaceHudMode.INK_CARD, hudTopInsetDp = 40)

        assertNull(card.backgroundColor)
        assertEquals(0, card.paddingLeftDp)
        assertEquals(0, card.paddingTopDp)
        assertEquals(0, card.paddingRightDp)
        assertEquals(0, card.paddingBottomDp)

        val fullBleed = surfaceHostChrome(SurfaceHudMode.FULL_BLEED, hudTopInsetDp = 40)

        assertEquals(BusTheme.glassesBg, fullBleed.backgroundColor)
        assertEquals(18, fullBleed.paddingLeftDp)
        assertEquals(16 + HudTopInset.sanitize(40), fullBleed.paddingTopDp)
        assertEquals(18, fullBleed.paddingRightDp)
        assertEquals(12, fullBleed.paddingBottomDp)
    }

    @Test
    fun `short card keeps content height and capped card lays out at the cap`() {
        val shortProbe = MeasuringView(context, desiredWidth = 441, desiredHeight = 260)
        val shortHost = host(shortProbe, heightCapPx = 500)
        measureAndLayout(shortHost, widthPx = 441, availableHeightPx = 620)

        assertEquals(441, shortHost.measuredWidth)
        assertEquals(260, shortHost.measuredHeight)
        assertEquals(260, shortProbe.measuredHeight)
        assertEquals(View.MeasureSpec.AT_MOST, shortProbe.lastHeightMode)

        val tallProbe = MeasuringView(context, desiredWidth = 441, desiredHeight = 800)
        val tallHost = host(tallProbe, heightCapPx = 500)
        measureAndLayout(tallHost, widthPx = 441, availableHeightPx = 620)

        assertEquals(500, tallHost.measuredHeight)
        assertEquals(500, tallProbe.measuredHeight)
        assertEquals(View.MeasureSpec.EXACTLY, tallProbe.lastHeightMode)
        assertEquals(2, tallProbe.measureCount)
    }

    @Test
    fun `clip and alpha reveal never remeasure or resize the final card`() {
        val probe = MeasuringView(context, desiredWidth = 441, desiredHeight = 420)
        val host = host(probe, heightCapPx = 600)
        measureAndLayout(host, widthPx = 441, availableHeightPx = 600)
        val params = probe.layoutParams
        val measureCount = probe.measureCount
        val childBounds = Rect(probe.left, probe.top, probe.right, probe.bottom)

        host.revealTo(84)
        probe.alpha = 0f
        assertEquals(Rect(0, 0, 441, 84), host.clipBounds)
        host.revealTo(252)
        probe.alpha = 0.5f
        host.revealTo(420)
        probe.alpha = 1f

        assertEquals(measureCount, probe.measureCount)
        assertEquals(441, probe.measuredWidth)
        assertEquals(420, probe.measuredHeight)
        assertEquals(childBounds, Rect(probe.left, probe.top, probe.right, probe.bottom))
        assertSame(params, probe.layoutParams)
        assertEquals(View.VISIBLE, probe.visibility)
        assertEquals(1f, probe.scaleX)
        assertEquals(1f, probe.scaleY)

        host.revealFully()
        host.layout(0, 0, 440, host.measuredHeight)
        assertNull(host.clipBounds)
    }

    private fun host(child: View, heightCapPx: Int): InkCardClipHost =
        InkCardClipHost(context).apply {
            this.heightCapPx = heightCapPx
            addView(
                child,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun measureAndLayout(host: InkCardClipHost, widthPx: Int, availableHeightPx: Int) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeightPx, View.MeasureSpec.AT_MOST),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    private fun surface(kind: String): NexusSurface = NexusSurface(
        surfaceId = "assistant:surface",
        seq = 1L,
        kind = kind,
        contentKey = "",
        title = "",
        subtitle = "",
        footer = "",
        rows = emptyList(),
        timedLines = emptyList(),
        anchor = null,
        handlesBack = false,
        ink = if (kind == NexusSurface.KIND_INK) InkSurfacePayload(documentJson = "{}") else null,
    )

    private class MeasuringView(
        context: Context,
        private val desiredWidth: Int,
        private val desiredHeight: Int,
    ) : View(context) {
        var measureCount = 0
            private set
        var lastHeightMode = View.MeasureSpec.UNSPECIFIED
            private set

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measureCount += 1
            lastHeightMode = View.MeasureSpec.getMode(heightMeasureSpec)
            setMeasuredDimension(
                resolve(desiredWidth, widthMeasureSpec),
                resolve(desiredHeight, heightMeasureSpec),
            )
        }

        private fun resolve(desired: Int, spec: Int): Int = when (View.MeasureSpec.getMode(spec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(spec)
            View.MeasureSpec.AT_MOST -> desired.coerceAtMost(View.MeasureSpec.getSize(spec))
            else -> desired
        }
    }
}
