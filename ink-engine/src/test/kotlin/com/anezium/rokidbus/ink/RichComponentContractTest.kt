package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichComponentContractTest {
    @Test
    fun `chart accepts official types aliases and typed multi-series data`() {
        val result = compilePage(
            """
                <chart type="area" series="{{series}}" data="{{points}}" animate="true"
                    smooth="false" showAverage="true" yAxis="{{yAxis}}" x-axis="{{xAxis}}"
                    width="350" height="120" />
            """,
            data = JSONObject()
                .put(
                    "series",
                    JSONArray()
                        .put(JSONObject().put("yName", "value").put("xName", "time").put("width", 2))
                        .put(JSONObject().put("yKey", "baseline").put("xKey", "time").put("smooth", false)),
                )
                .put(
                    "points",
                    JSONArray()
                        .put(JSONObject().put("time", "08:00").put("value", 10).put("baseline", 8))
                        .put(JSONObject().put("time", "08:01").put("value", 14).put("baseline", 9)),
                )
                .put("yAxis", JSONObject().put("minimum", 0).put("maximum", 20))
                .put("xAxis", JSONObject().put("valueType", "Category")),
        )

        assertFalse(result.problems.toString(), result.hasErrors)
        val chart = result.requireDocument().roots.single()
        assertEquals("chart", chart.type)
        assertTrue(chart.attributes["series"] is List<*>)
        assertEquals(true, chart.attributes["animate"])
        assertEquals(350.0, chart.attributes["width"])
    }

    @Test
    fun `chart rejects out-of-matrix type and enforces series and point budgets`() {
        val tooManySeries = List(InkWireLimits.MAX_CHART_SERIES + 1) { index ->
            mapOf("yName" to "v$index")
        }
        val tooManyPoints = List(InkWireLimits.MAX_CHART_POINTS + 1) { index -> mapOf("value" to index) }
        val document = RenderDocument(
            listOf(
                RenderNode(
                    "chart",
                    "chart",
                    attributes = mapOf("type" to "scatter", "series" to tooManySeries, "data" to tooManyPoints),
                ),
            ),
            documentId = "doc",
        )

        val problems = InkWireValidator.validateDocument(document)
        assertTrue(problems.any { it.code == InkProblemCodes.COMPONENT_VALUE && it.feature == "$.roots[0].a.type" })
        assertEquals(2, problems.count { it.code == InkProblemCodes.COMPONENT_BUDGET })
    }

    @Test
    fun `bar compiles with an explicit sample-derived warning`() {
        val result = compilePage(
            "<chart type=\"bar\" series=\"value\" data=\"{{points}}\" direction=\"vertical\" />",
            data = JSONObject().put("points", JSONArray().put(JSONObject().put("label", "A").put("value", 4))),
        )

        assertFalse(result.problems.toString(), result.hasErrors)
        assertTrue(result.problems.any { it.code == InkProblemCodes.COMPONENT_SAMPLE_DERIVED })
    }

    @Test
    fun `lottie accepts inline JSON and rejects paths and oversized inline payloads`() {
        val inline = """{"v":"5.7.4","fr":30,"ip":0,"op":1,"w":8,"h":8,"layers":[]}"""
        val accepted = compilePage(
            "<lottie-view src=\"{{animation}}\" auto-play=\"false\" loop=\"true\" speed=\"1.5\" progress=\"0.25\" />",
            data = JSONObject().put("animation", inline),
        )
        assertFalse(accepted.problems.toString(), accepted.hasErrors)
        assertEquals(inline, accepted.requireDocument().roots.single().attributes["src"])

        val remote = compilePage("<lottie-view src=\"https://example.com/loading.json\" />")
        assertTrue(remote.problems.any { it.code == InkProblemCodes.ATTRIBUTE_SOURCE })

        val oversized = RenderDocument(
            listOf(
                RenderNode(
                    "lottie",
                    "lottie-view",
                    attributes = mapOf("src" to "{\"x\":\"${"x".repeat(InkWireLimits.MAX_LOTTIE_JSON_BYTES)}\"}"),
                ),
            ),
            documentId = "doc",
        )
        assertTrue(
            InkWireValidator.validateDocument(oversized)
                .any { it.code == InkProblemCodes.COMPONENT_BUDGET && it.feature?.endsWith(".a.src") == true },
        )
    }

    @Test
    fun `progress maps documented values and reports typed range failures`() {
        val accepted = compilePage(
            "<progress percent=\"{{percent}}\" show-info=\"true\" stroke-width=\"8\" active=\"true\" duration=\"280\" />",
            data = JSONObject().put("percent", 42),
        )
        assertFalse(accepted.problems.toString(), accepted.hasErrors)
        assertEquals(42, accepted.requireDocument().roots.single().attributes["percent"])

        val invalid = compilePage("<progress percent=\"101\" show-info=\"sometimes\" />")
        assertTrue(
            invalid.problems.any {
                it.code == InkProblemCodes.ATTRIBUTE_VALUE && it.feature?.endsWith(".percent") == true
            },
        )
        val normalized = compilePage("<progress percent=\"10\" show-info=\"sometimes\" />")
        // Literal booleans are normalized exactly like other Ink boolean attributes.
        assertEquals(false, normalized.requireDocument().roots.single().attributes["show-info"])
    }

    @Test
    fun `nx-canvas accepts exact canvas names and enforces vocabulary and command budget`() {
        val commands = JSONArray()
            .put(JSONObject().put("name", "beginPath").put("args", JSONArray()))
            .put(JSONObject().put("name", "moveTo").put("args", JSONArray().put(0).put(0)))
            .put(JSONObject().put("name", "lineTo").put("args", JSONArray().put(20).put(20)))
            .put(JSONObject().put("name", "stroke").put("args", JSONArray()))
        val accepted = compilePage(
            "<nx-canvas commands=\"{{commands}}\" width=\"300\" height=\"150\" />",
            data = JSONObject().put("commands", commands),
        )
        assertFalse(accepted.problems.toString(), accepted.hasErrors)
        assertNotNull(accepted.document)

        val unknown = compilePage(
            "<nx-canvas commands=\"{{commands}}\" />",
            data = JSONObject().put(
                "commands",
                JSONArray().put(JSONObject().put("name", "drawNetworkImage").put("args", JSONArray())),
            ),
        )
        assertTrue(unknown.problems.any { it.code == InkProblemCodes.COMPONENT_VALUE })

        val tooMany = List(InkWireLimits.MAX_CANVAS_COMMANDS + 1) {
            mapOf("name" to "beginPath", "args" to emptyList<Any?>())
        }
        val oversized = RenderDocument(
            listOf(RenderNode("canvas", "nx-canvas", attributes = mapOf("commands" to tooMany))),
            documentId = "doc",
        )
        assertTrue(InkWireValidator.validateDocument(oversized).any { it.code == InkProblemCodes.COMPONENT_BUDGET })
    }
}
