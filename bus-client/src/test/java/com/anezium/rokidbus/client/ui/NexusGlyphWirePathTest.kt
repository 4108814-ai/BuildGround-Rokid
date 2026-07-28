package com.anezium.rokidbus.client.ui

import com.anezium.rokidbus.shared.GlyphContract
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class NexusGlyphWirePathTest {

    @Test
    fun `plugin glyph wire paths stay in sync with their drawables`() {
        val arrayFiles = pluginsDir().walkTopDown()
            .filter { it.isFile && it.name == "nexus_glyphs.xml" }
            .filterNot { it.invariantSeparatorsPath.contains("/build/") }
            .sortedBy { it.path }
            .toList()
        assertTrue("no plugin glyph string-arrays found", arrayFiles.isNotEmpty())

        arrayFiles.forEach(::assertWirePathsMatchDrawables)
    }

    private fun assertWirePathsMatchDrawables(arrayFile: File) {
        val array = parse(arrayFile)
            .getElementsByTagName("string-array")
            .asElements()
            .singleOrNull { it.getAttribute("name") == "nexus_glyphs" }
        assertTrue("${displayPath(arrayFile)}: must declare string-array nexus_glyphs", array != null)
        if (array == null) return

        val entries = array.getElementsByTagName("item")
            .asElements()
            .map { it.textContent.trim() }
        val parsed = GlyphContract.parse(entries)
        assertTrue(
            "${displayPath(arrayFile)}: nexus_glyphs entries must be valid, got $parsed",
            parsed is GlyphContract.ParseResult.Valid,
        )
        if (parsed !is GlyphContract.ParseResult.Valid) return

        val pluginDir = pluginDirFor(arrayFile)

        // Every mark this plugin draws has to be on the wire, or the glasses
        // cannot draw it. Without this the rest of the test is defeated by a
        // rename: rename the array entry alone and nothing matches the drawable
        // any more, so the comparison below silently checks nothing while the
        // glasses quietly fall back to the grid.
        pluginDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("nexus_glyph_") && it.extension == "xml" }
            .filterNot { it.invariantSeparatorsPath.contains("/build/") }
            .sortedBy { it.path }
            .forEach { drawable ->
                val name = drawable.nameWithoutExtension.removePrefix("nexus_glyph_")
                assertTrue(
                    "${displayPath(drawable)} has no entry in ${displayPath(arrayFile)}. " +
                        "A plugin that declares a glyph array must list every mark it draws, " +
                        "otherwise that mark reaches the phone and not the glasses.",
                    parsed.glyphs.any { it.name == name },
                )
            }

        parsed.glyphs.forEach { glyph ->
            pluginDir.walkTopDown()
                .filter { it.isFile && it.name == "nexus_glyph_${glyph.name}.xml" }
                .filterNot { it.invariantSeparatorsPath.contains("/build/") }
                .sortedBy { it.path }
                .forEach { drawable ->
                    val drawablePath = parse(drawable)
                        .getElementsByTagName("path")
                        .asElements()
                        .joinToString(" ") {
                            it.getAttributeNS(ANDROID_NAMESPACE, "pathData")
                        }
                    assertEquals(
                        "${displayPath(arrayFile)} drifted from ${displayPath(drawable)}. " +
                            "Regenerate ${displayPath(arrayFile)} from ${displayPath(drawable)}; " +
                            "the array is regenerated from the drawable, not the reverse.",
                        drawablePath,
                        glyph.pathData,
                    )
                }
        }
    }

    private fun pluginDirFor(file: File): File {
        val plugins = pluginsDir().canonicalFile
        return generateSequence(file.canonicalFile.parentFile) { it.parentFile }
            .first { it.parentFile == plugins }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun pluginsDir(): File {
        val local = File("../plugins")
        return if (local.isDirectory) local else File("plugins")
    }

    private fun displayPath(file: File): String =
        file.canonicalFile.relativeTo(pluginsDir().canonicalFile.parentFile!!)
            .invariantSeparatorsPath

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
