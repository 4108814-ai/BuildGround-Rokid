package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSampleCorpusTest {
    @Test
    fun `official layout sample binds with only documented out-of-matrix warnings`() {
        val result = compileFixture("layout")

        assertFalse("${result.problems}", result.hasErrors)
        assertNotNull(result.document)
        assertTrue(result.document!!.nodeCount > 50)
        assertEquals(
            setOf(InkProblemCodes.STYLE_UNSUPPORTED, InkProblemCodes.COLOR_LITERAL),
            result.problems.mapTo(sortedSetOf()) { it.code },
        )
        assertEquals(
            setOf("background", "box-shadow", "display"),
            result.problems.filter { it.code == InkProblemCodes.STYLE_UNSUPPORTED }.mapTo(sortedSetOf()) { it.feature },
        )
    }

    @Test
    fun `official position sample binds and flags fixed and non-matrix effects`() {
        val result = compileFixture("position")

        assertFalse("${result.problems}", result.hasErrors)
        assertNotNull(result.document)
        assertEquals(
            setOf("box-shadow", "position", "z-index"),
            result.problems.filter { it.code == InkProblemCodes.STYLE_UNSUPPORTED }.mapTo(sortedSetOf()) { it.feature },
        )
        assertTrue(result.problems.any { it.code == InkProblemCodes.COLOR_LITERAL && it.feature == "background-color" })
    }

    @Test
    fun `official grid sample is rejected by exact selector and property categories`() {
        val result = compileFixture("grid")

        assertNull(result.document)
        assertEquals(7, result.problems.count { it.code == InkProblemCodes.SELECTOR_UNSUPPORTED })
        assertEquals(
            setOf(
                "display",
                "grid-template-columns",
                "grid-template-rows",
            ),
            result.problems.filter { it.code == InkProblemCodes.STYLE_UNSUPPORTED }.mapTo(sortedSetOf()) { it.feature },
        )
        assertTrue(result.problems.filter { it.code == InkProblemCodes.SELECTOR_UNSUPPORTED }.any { it.feature == "@media" })
    }

    private fun compileFixture(name: String): InkCompileResult = InkEngine.compile(
        InkSource.MultiFile(resource("$name.wxml"), resource("$name.wxss"), JSONObject()),
        JSONObject(),
    )

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/official/$name")).readText()
}
