package com.anezium.rokidbus.client.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import com.anezium.rokidbus.shared.GlyphContract

/**
 * Draws a plugin-supplied glyph in the platform's own style.
 *
 * The plugin gives geometry; everything visible about it is decided here. It
 * cannot pick the colour, the stroke width, the caps, or the size — which is
 * why a custom glyph can extend the set without being able to break how it
 * reads. That is the same inversion the rest of the HUD rests on, moved down to
 * the one place a plugin could otherwise have smuggled a look past us.
 *
 * The path is authored on the same 24-unit viewport as every bundled vector, so
 * it scales to the bounds the way the built-ins do.
 */
class GlyphDrawable(pathData: String) : Drawable() {

    private val source: Path? = runCatching { PathParser.createPathFromPathData(pathData) }
        .getOrNull()

    private val scaled = Path()
    private val matrix = Matrix()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = NexusUi.GREEN
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** True when the path parsed. A malformed one draws nothing; callers fall back. */
    val isRenderable: Boolean get() = source != null

    override fun onBoundsChange(bounds: Rect) {
        val path = source ?: return
        val scale = minOf(bounds.width(), bounds.height()) / VIEWPORT
        matrix.setScale(scale, scale)
        matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        path.transform(matrix, scaled)
        // Stroke width is authored in viewport units, so it has to scale with
        // the shape or a glyph would go spidery at 48dp and blunt at 16dp.
        paint.strokeWidth = STROKE_WIDTH * scale
    }

    override fun draw(canvas: Canvas) {
        if (source == null) return
        canvas.drawPath(scaled, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable, still abstract.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = VIEWPORT.toInt()

    override fun getIntrinsicHeight(): Int = VIEWPORT.toInt()

    companion object {
        /** Same viewport as every bundled vector. See docs/GLYPHS.md. */
        const val VIEWPORT = 24f

        /** The design system's primary stroke, in viewport units. */
        const val STROKE_WIDTH = 1.7f

        /**
         * Build one from a validated glyph, or null when the path does not
         * parse — the caller falls back to `dot` rather than showing nothing.
         */
        fun from(glyph: GlyphContract.CustomGlyph): GlyphDrawable? =
            GlyphDrawable(glyph.pathData).takeIf { it.isRenderable }
    }
}
