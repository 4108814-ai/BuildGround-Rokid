package com.anezium.rokidbus.glasses

import android.graphics.drawable.Drawable
import com.anezium.rokidbus.client.ui.GlyphDrawable
import com.anezium.rokidbus.shared.GlyphContract
import java.util.concurrent.ConcurrentHashMap

class PluginGlyphCache {
    private val glyphsByPlugin =
        ConcurrentHashMap<String, Map<String, GlyphContract.CustomGlyph>>()

    fun put(pluginId: String, glyphs: List<GlyphContract.CustomGlyph>) {
        glyphsByPlugin[pluginId] = glyphs.associateBy(GlyphContract.CustomGlyph::name)
    }

    fun glyph(pluginId: String, name: String): GlyphContract.CustomGlyph? =
        glyphsByPlugin[pluginId]?.get(name)

    /**
     * A fresh instance every call, deliberately. [Drawable] bounds are mutable
     * state, so one shared instance handed to two views would let the second
     * resize the first. Parsing a path is cheap next to that class of bug.
     *
     * Null when the name is unknown or the path is not one; the caller falls back
     * the same way it does for an unknown `iconKey`.
     *
     * The [GlyphContract.isWellFormedPath] check is **not** redundant with
     * [GlyphDrawable.from]. `PathParser` does not reject garbage — it hands back
     * an empty path for a string like `"not a path"` rather than throwing — so
     * `from` returning non-null means "did not crash", not "is a shape". The
     * character-level check is the real gate. Removing it draws nothing, visibly,
     * instead of falling back to a dot.
     */
    fun drawableFor(pluginId: String, name: String): Drawable? {
        val glyph = glyph(pluginId, name) ?: return null
        if (!GlyphContract.isWellFormedPath(glyph.pathData)) return null
        return GlyphDrawable.from(glyph)
    }
}
