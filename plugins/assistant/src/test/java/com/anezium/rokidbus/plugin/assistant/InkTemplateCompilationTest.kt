package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkSource
import com.anezium.rokidbus.ink.RenderNode
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTemplateCompilationTest {
    private val validator = InkTemplateValidator()

    @Test
    fun `weather compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.WEATHER,
            Case(
                data = json(
                    """{"temperature":"18 C","condition":"Clear","forecast":[{"label":"Tonight","temperature":"12 C"}]}""",
                ),
                expectedText = "Tonight",
            ),
            Case(
                data = json(
                    """{"location":"Paris","temperature":"24 C","condition":"Sunny","high":"27 C","low":"16 C","forecast":[{"label":"Now","temperature":"24 C","condition":"Sunny"},{"label":"18:00","temperature":"21 C","condition":"Clear"},{"label":"Tomorrow","temperature":"19 C","condition":"Cloudy"}]}""",
                ),
                expectedText = "Paris",
            ),
        )
    }

    @Test
    fun `chart compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.CHART,
            Case(
                data = json(
                    """{"type":"line","labels":["Now"],"series":[{"label":"Value","values":[7]}]}""",
                ),
                expectedText = "Value",
            ),
            Case(
                data = json(
                    """{"type":"area","labels":["Q1","Q2","Q3"],"series":[{"label":"Revenue","values":[12,18,24]},{"label":"Cost","values":[8,11,13]}],"caption":"Quarterly totals"}""",
                ),
                expectedText = "Revenue",
            ),
        )
    }

    @Test
    fun `metrics compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.METRICS,
            Case(
                data = json(
                    """{"cells":[{"label":"Uptime","value":"99.9%"},{"label":"Errors","value":"0"}]}""",
                ),
                expectedText = "99.9%",
            ),
            Case(
                data = json(
                    """{"cells":[{"label":"Latency","value":"42 ms","detail":"p50"},{"label":"Requests","value":"8.2k"},{"label":"Success","value":"99.8%"},{"label":"Queue","value":"3"}]}""",
                ),
                expectedText = "42 ms",
            ),
        )
    }

    @Test
    fun `ranking compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.RANKING,
            Case(
                data = json("""{"rows":[{"label":"First","value":"10"}]}"""),
                expectedText = "First",
            ),
            Case(
                data = json(
                    """{"rows":[{"label":"Ada","value":"98","detail":"+4"},{"label":"Lin","value":"91"},{"label":"Sam","value":"87"}]}""",
                ),
                expectedText = "Ada",
            ),
        )
    }

    @Test
    fun `comparison compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.COMPARISON,
            Case(
                data = json(
                    """{"left":{"label":"Option A","items":[{"label":"Price","value":"10"}]},"right":{"label":"Option B","items":[{"label":"Price","value":"12"}]}}""",
                ),
                expectedText = "Option A",
            ),
            Case(
                data = json(
                    """{"left":{"label":"Alpha","items":[{"label":"Speed","value":"Fast"},{"label":"Cost","value":"Low"}]},"right":{"label":"Beta","items":[{"label":"Speed","value":"Medium"},{"label":"Cost","value":"High"}]},"verdict":"Alpha leads overall"}""",
                ),
                expectedText = "Alpha leads overall",
            ),
        )
    }

    @Test
    fun `schedule compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.SCHEDULE,
            Case(
                data = json("""{"entries":[{"time":"09:00","title":"Stand-up"}]}"""),
                expectedText = "Stand-up",
            ),
            Case(
                data = json(
                    """{"entries":[{"time":"09:00","title":"Inbox"},{"time":"10:30","title":"Design review","detail":"Room 4B"},{"time":"14:00","title":"Planning","detail":"30 min"}]}""",
                ),
                expectedText = "Design review",
            ),
        )
    }

    @Test
    fun `steps compiles with minimal and representative data`() {
        assertTemplateCases(
            InkTemplateId.STEPS,
            Case(
                data = json("""{"current":0,"steps":[{"label":"Start"}]}"""),
                expectedText = "Start",
            ),
            Case(
                data = json(
                    """{"current":3,"steps":[{"label":"Plan","detail":"Scope agreed"},{"label":"Build"},{"label":"Verify"},{"label":"Ship release"}]}""",
                ),
                expectedText = "Ship release",
            ),
        )
    }

    private fun assertTemplateCases(template: InkTemplateId, vararg cases: Case) {
        val source = loadTemplate(template)
        cases.forEach { case ->
            val validation = validator.validate(template, case.data)
            assertTrue(
                "Validation failed for ${template.wireValue}: $validation",
                validation is InkTemplateValidationResult.Valid,
            )
            val renderData = (validation as InkTemplateValidationResult.Valid).data
            val result = InkEngine.compile(InkSource.Sfc(source), renderData)

            assertTrue(
                "${template.wireValue} produced compiler problems: ${result.problems}",
                result.problems.isEmpty(),
            )
            val document = checkNotNull(result.document) {
                "${template.wireValue} did not produce a document"
            }
            val renderedText = document.roots.joinToString(" ") { it.renderedText() }
            assertTrue(
                "${template.wireValue} did not render '${case.expectedText}': $renderedText",
                case.expectedText in renderedText,
            )
        }
    }

    private fun loadTemplate(template: InkTemplateId): String {
        val path = "ink_templates/${template.wireValue}.ink"
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun RenderNode.renderedText(): String = buildString {
        text?.let(::append)
        children.forEach { append(it.renderedText()) }
    }

    private data class Case(
        val data: JSONObject,
        val expectedText: String,
    )

    private companion object {
        fun json(text: String): JSONObject = JSONObject(text)
    }
}
