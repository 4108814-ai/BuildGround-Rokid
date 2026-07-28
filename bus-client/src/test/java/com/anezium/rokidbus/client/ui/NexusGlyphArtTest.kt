package com.anezium.rokidbus.client.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design system, enforced instead of described.
 *
 * `docs/GLYPHS.md` explains the rules to a human; this is what stops them
 * drifting. It was written after finding that our own PhotoSync glyph had
 * quietly diverged from the shipped set — prose in a doc had not been enough.
 *
 * The rules below are deliberately the *invariants*, not a template. Craft
 * inside them is expected: `ic_plugin_bus` renders its headlights as
 * zero-length round-capped strokes at 1.9 and its wheels at 1.5, and that is
 * good work, not a violation. What must never vary is the size, the single
 * colour, and the presence of the 1.7 primary stroke that makes the family
 * read as one set.
 */
class NexusGlyphArtTest {

    private val phosphor = "#FF4DFF8C"

    @Test
    fun `every shipped vector follows the glyph design system`() {
        val files = drawableDir().listFiles { f: File -> f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no drawables found — is the test running from the module dir?", files.isNotEmpty())
        files.forEach(::assertFollowsDesignSystem)
    }

    @Test
    fun `plugin-supplied glyphs in this repo follow it too`() {
        // Plugins are the case the system exists for: they are authored outside
        // bus-client and land on a HUD next to icons they did not draw.
        val pluginGlyphs = File("../plugins").walkTopDown()
            .filter { it.isFile && it.name.startsWith("nexus_glyph_") && it.extension == "xml" }
            .filterNot { it.invariantSeparatorsPath.contains("/build/") }
            .sortedBy { it.path }
            .toList()
        assertTrue("no plugin glyphs found", pluginGlyphs.isNotEmpty())
        pluginGlyphs.forEach(::assertFollowsDesignSystem)
    }

    private fun assertFollowsDesignSystem(file: File) {
        val xml = file.readText()
        val where = file.name

        assertTrue("$where: must be 24dp square", xml.contains("""android:width="24dp""""))
        assertTrue("$where: must be 24dp square", xml.contains("""android:height="24dp""""))
        assertTrue("$where: viewport must be 24", xml.contains("""android:viewportWidth="24""""))
        assertTrue("$where: viewport must be 24", xml.contains("""android:viewportHeight="24""""))

        // One colour, and it is the phosphor. This is the rule that keeps a
        // full-colour logo from landing on a monochrome optic as a green blob.
        val colours = COLOUR.findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
        assertTrue(
            "$where: only $phosphor is allowed, found $colours",
            colours.isNotEmpty() && colours.all { it == phosphor },
        )

        // The primary shape carries the family's weight. Details may deviate.
        assertTrue(
            "$where: needs at least one path stroked at 1.7 — a fill-only glyph " +
                "reads as a solid blob on additive optics and breaks the set",
            xml.contains("""android:strokeWidth="1.7""""),
        )

        assertTrue("$where: gradients are not monochrome", !xml.contains("<gradient"))
    }

    private fun drawableDir(): File {
        // Unit tests run with the module directory as the working directory.
        val local = File("src/main/res/drawable")
        return if (local.isDirectory) local else File("bus-client/src/main/res/drawable")
    }

    private companion object {
        val COLOUR = Regex("""android:(?:stroke|fill)Color="(#[0-9A-Fa-f]{6,8})"""")
    }
}
