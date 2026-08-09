package com.anezium.rokidbus.ink

import org.json.JSONArray
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

    @Test
    fun `official chart sample derivative compiles guaranteed types and flags bar origin`() {
        val result = InkEngine.compile(
            InkSource.MultiFile(resource("chart-rich.wxml"), "", JSONObject()),
            chartData(),
        )

        assertFalse(result.problems.toString(), result.hasErrors)
        assertEquals(5, result.document!!.allNodes().count { it.type == "chart" })
        assertEquals(
            listOf(InkProblemCodes.COMPONENT_SAMPLE_DERIVED),
            result.problems.map { it.code },
        )
    }

    @Test
    fun `official chart sample out-of-matrix families have exact typed problems`() {
        val result = InkEngine.compile(
            InkSource.MultiFile(resource("chart-out-of-matrix.wxml"), "", JSONObject()),
            chartData(),
        )

        assertNull(result.document)
        assertEquals(
            listOf(InkProblemCodes.COMPONENT_VALUE, InkProblemCodes.COMPONENT_VALUE),
            result.problems.map { it.code },
        )
        assertEquals(
            setOf("funnel", "scatter"),
            result.problems.mapTo(sortedSetOf()) { it.message.substringAfter("Chart type '").substringBefore("'") },
        )
    }

    private fun chartData(): JSONObject {
        val points = JSONArray()
            .put(JSONObject().put("time", 0).put("traffic", 10).put("baseline", 8))
            .put(JSONObject().put("time", 1).put("traffic", 14).put("baseline", 8))
        return JSONObject()
            .put(
                "line",
                JSONObject()
                    .put(
                        "series",
                        JSONArray()
                            .put(JSONObject().put("yName", "traffic").put("xName", "time"))
                            .put(JSONObject().put("yName", "baseline").put("xName", "time")),
                    )
                    .put("data", points)
                    .put("yAxis", JSONObject().put("minimum", 0).put("maximum", 20))
                    .put("xAxis", JSONObject().put("minimum", 0).put("maximum", 2)),
            )
            .put("retention", JSONArray().put(JSONObject().put("score", 72)))
            .put("channels", JSONArray().put(JSONObject().put("value", 64)))
            .put("sources", JSONArray().put(JSONObject().put("value", 34)))
            .put("radar", JSONArray().put(JSONObject().put("value", 82)))
            .put("funnel", JSONArray().put(JSONObject().put("value", 10)))
            .put("scatter", JSONArray().put(JSONObject().put("value", 10)))
    }

    private fun compileFixture(name: String): InkCompileResult = InkEngine.compile(
        InkSource.MultiFile(resource("$name.wxml"), resource("$name.wxss"), JSONObject()),
        JSONObject(),
    )

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/official/$name")).readText()
}
