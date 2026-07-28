package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphContractTest {

    @Test
    fun `parses the declared glyphs`() {
        val result = GlyphContract.parse(
            listOf(
                "photosync|M2,9 A2,2 0 0 1 4,7 L18,7 Z",
                "  courier|M4,4 L20,20  ",
                "   ",
            ),
        )
        val glyphs = (result as GlyphContract.ParseResult.Valid).glyphs
        assertEquals(2, glyphs.size)
        assertEquals("photosync", glyphs[0].name)
        assertEquals("M2,9 A2,2 0 0 1 4,7 L18,7 Z", glyphs[0].pathData)
        assertEquals("courier", glyphs[1].name)
        assertEquals("M4,4 L20,20", glyphs[1].pathData)
    }

    @Test
    fun `parses the actual photosync concatenated path`() {
        val pathData =
            "M2,9 A2,2 0 0 1 4,7 L7.2,7 L8.5,4.8 L13.5,4.8 L14.8,7 L18,7 " +
                "A2,2 0 0 1 20,9 L20,17 A2,2 0 0 1 18,19 L4,19 A2,2 0 0 1 2,17 Z " +
                "M7.8,13 A3.2,3.2 0 1 0 14.2,13 A3.2,3.2 0 1 0 7.8,13 " +
                "M18.6,5.6 L18.6,1.6 M18.6,1.6 L16.4,3.8 M18.6,1.6 L20.8,3.8"

        val result = GlyphContract.parse(listOf("photosync|$pathData"))

        val glyphs = (result as GlyphContract.ParseResult.Valid).glyphs
        assertEquals(listOf(GlyphContract.CustomGlyph("photosync", pathData)), glyphs)
    }

    @Test
    fun `names are shape-checked, never membership-checked`() {
        // A name this build has never heard of is valid on purpose: it renders
        // as a dot rather than being refused, which is what keeps the set
        // additive instead of a version gate.
        assertTrue(GlyphContract.isWellFormedName("some-future-glyph"))
        assertTrue(GlyphContract.isWellFormedName("dot"))

        assertFalse(GlyphContract.isWellFormedName(null))
        assertFalse(GlyphContract.isWellFormedName(""))
        assertFalse(GlyphContract.isWellFormedName("Turn-Left"))
        assertFalse(GlyphContract.isWellFormedName("turn_left"))
        assertFalse(GlyphContract.isWellFormedName("turn--left"))
        assertFalse(GlyphContract.isWellFormedName("-turn"))
        assertFalse(GlyphContract.isWellFormedName("turn-"))
        assertFalse(GlyphContract.isWellFormedName("x".repeat(GlyphContract.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun `paths accept only path data`() {
        assertTrue(GlyphContract.isWellFormedPath("M2,9 A2,2 0 0 1 4,7 Z"))
        assertTrue(GlyphContract.isWellFormedPath("m0,0 l1.5e2,-3 c1,2 3,4 5,6"))

        assertFalse("must start with a move", GlyphContract.isWellFormedPath("L4,4"))
        assertFalse(GlyphContract.isWellFormedPath(""))
        assertFalse(GlyphContract.isWellFormedPath(null))
        assertFalse(GlyphContract.isWellFormedPath("M0,0 " + "L1,1".repeat(GlyphContract.MAX_PATH_LENGTH)))
    }

    @Test
    fun `nothing but geometry rides along in a path`() {
        // The point of the character allowlist: a plugin supplies a shape, and
        // has no way to smuggle markup, a URL, or a colour past it.
        listOf(
            """M0,0" android:fillColor="#FF0000""",
            "M0,0 <path/>",
            "M0,0 url(https://example.com)",
            "M0,0 #FF4DFF8C",
        ).forEach { assertFalse(it, GlyphContract.isWellFormedPath(it)) }
    }

    @Test
    fun `a malformed entry is rejected rather than half-parsed`() {
        assertEquals(
            GlyphContract.ERROR_INVALID_NAME,
            reasonFor(listOf("no-separator-here")),
        )
        assertEquals(
            GlyphContract.ERROR_INVALID_NAME,
            reasonFor(listOf("|M0,0")),
        )
        assertEquals(
            GlyphContract.ERROR_INVALID_PATH,
            reasonFor(listOf("fine|not a path")),
        )
        assertEquals(
            GlyphContract.ERROR_DUPLICATE_NAME,
            reasonFor(listOf("same|M0,0", "same|M1,1")),
        )
        assertEquals(
            GlyphContract.ERROR_TOO_MANY,
            reasonFor(names(GlyphContract.MAX_GLYPHS_PER_PLUGIN + 1)),
        )
    }

    @Test
    fun `the cap is a handful of marks, not an icon theme`() {
        assertTrue(
            GlyphContract.parse(names(GlyphContract.MAX_GLYPHS_PER_PLUGIN))
                is GlyphContract.ParseResult.Valid,
        )
    }

    @Test
    fun `digits are not part of a glyph name`() {
        // Letters and hyphens only, matching the built-in set. Loosening this
        // later is safe; a wire contract that started permissive could not be
        // tightened without breaking whoever relied on it.
        assertFalse(GlyphContract.isWellFormedName("mp3"))
    }

    private fun names(count: Int): List<String> =
        ('a'..'z').take(count).map { "glyph-$it|M0,0 L1,1" }

    private fun reasonFor(entries: List<String>): String =
        (GlyphContract.parse(entries) as GlyphContract.ParseResult.Invalid).reason
}
