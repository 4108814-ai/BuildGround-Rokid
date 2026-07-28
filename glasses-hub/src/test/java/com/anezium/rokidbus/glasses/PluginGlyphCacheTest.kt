package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlyphContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric because building a glyph parses an android.graphics.Path, which a
// plain JVM test would stub into throwing. The cache itself needs no framework.
@RunWith(RobolectricTestRunner::class)
class PluginGlyphCacheTest {

    @Test
    fun `put and get preserve the parsed glyph`() {
        val cache = PluginGlyphCache()
        val glyph = GlyphContract.CustomGlyph("mark", "M2,2 L22,22")

        cache.put("one", listOf(glyph))

        assertSame(glyph, cache.glyph("one", "mark"))
        assertNull(cache.glyph("one", "unknown"))
    }

    @Test
    fun `drawable lookup rejects unknown names and malformed paths`() {
        val cache = PluginGlyphCache()
        cache.put("one", listOf(GlyphContract.CustomGlyph("broken", "not a path")))

        assertNull(cache.drawableFor("one", "unknown"))
        assertNull(cache.drawableFor("one", "broken"))
    }

    @Test
    fun `each drawable lookup creates fresh mutable state`() {
        val cache = PluginGlyphCache()
        cache.put("one", listOf(GlyphContract.CustomGlyph("mark", "M2,2 L22,22")))

        val first = cache.drawableFor("one", "mark")
        val second = cache.drawableFor("one", "mark")

        assertNotSame(first, second)
    }

    @Test
    fun `put fully replaces one plugin without touching another namespace`() {
        val cache = PluginGlyphCache()
        val first = GlyphContract.CustomGlyph("mark", "M1,1 L2,2")
        val replacement = GlyphContract.CustomGlyph("new-mark", "M3,3 L4,4")
        val other = GlyphContract.CustomGlyph("mark", "M5,5 L6,6")
        cache.put("one", listOf(first))
        cache.put("two", listOf(other))

        cache.put("one", listOf(replacement))

        assertNull(cache.glyph("one", "mark"))
        assertEquals(replacement, cache.glyph("one", "new-mark"))
        assertEquals(other, cache.glyph("two", "mark"))
    }
}
