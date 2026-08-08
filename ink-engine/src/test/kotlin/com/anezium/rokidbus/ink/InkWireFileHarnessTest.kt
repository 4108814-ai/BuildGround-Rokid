package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in JVM file harness; see ink-engine/README.md for the environment variables. */
class InkWireFileHarnessTest {
    @Test
    fun `compile requested Ink wire files`() {
        val sourcePath = System.getenv("INK_SOURCE")?.takeIf(String::isNotBlank) ?: return
        val documentOut = requiredEnvironment("INK_DOCUMENT_OUT")
        val data = System.getenv("INK_DATA")?.takeIf(String::isNotBlank)?.let(::readJson) ?: JSONObject()
        val result = InkEngine.compile(InkSource.Sfc(Files.readString(Path.of(sourcePath))), data)
        assertFalse("Compile failed: ${result.problems}", result.hasErrors)
        Files.writeString(Path.of(documentOut), result.document!!.toWireJson())

        val patchDataPath = System.getenv("INK_PATCH_DATA")?.takeIf(String::isNotBlank) ?: return
        val patchOut = requiredEnvironment("INK_PATCH_OUT")
        val patch = result.session!!.applyPatch(readJson(patchDataPath))
        assertFalse("Patch failed: ${patch.problems}", patch.hasErrors)
        Files.writeString(Path.of(patchOut), patch.patch!!.toWireJson())
    }

    private fun readJson(path: String): JSONObject = JSONObject(Files.readString(Path.of(path)))

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) { "$name is required" }
}
