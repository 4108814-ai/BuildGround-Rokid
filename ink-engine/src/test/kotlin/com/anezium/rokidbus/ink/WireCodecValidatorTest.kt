package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecValidatorTest {
    @Test
    fun `document codec round trips every wire field`() {
        val source = compilePage(
            "<view id=\"root\" bindtap=\"open\" data-index=\"{{index}}\"><text>{{message}}</text></view>",
            ".unused { width: 10rpx; }",
            JSONObject("""{"index":2,"message":"hello"}"""),
        ).requireDocument()

        val decoded = RenderDocument.fromWireJson(source.toWireJson())

        assertFalse(decoded.hasErrors)
        assertNotNull(decoded.value)
        assertEquals(source.toWireJson(), decoded.value!!.toWireJson())
        assertTrue(InkWireValidator.validateDocument(decoded.value!!).isEmpty())
    }

    @Test
    fun `patch codec round trips and session revisions are consecutive`() {
        val compiled = compilePage("<text>{{value}}</text>", data = JSONObject().put("value", "a"))
        val first = compiled.session!!.applyPatch(JSONObject().put("value", "b"))
        val second = compiled.session.applyPatch(JSONObject().put("value", "c"))

        assertEquals(0, first.patch!!.baseRevision)
        assertEquals(1, first.patch.targetRevision)
        assertEquals(1, second.patch!!.baseRevision)
        assertEquals(2, second.patch.targetRevision)
        assertEquals(first.patch.documentId, second.patch.documentId)

        val decoded = RenderPatch.fromWireJson(second.patch.toWireJson())
        assertFalse(decoded.hasErrors)
        assertEquals(second.patch.toWireJson(), decoded.value!!.toWireJson())
        assertTrue(InkWireValidator.validatePatch(decoded.value!!).isEmpty())
    }

    @Test
    fun `problem report codec round trips warnings and locations`() {
        val report = InkProblemReport(
            listOf(
                InkProblem(
                    InkProblemCodes.COLOR_LITERAL,
                    "literal",
                    InkProblemSeverity.WARNING,
                    line = 4,
                    column = 7,
                    feature = "color",
                ),
            ),
        )

        val decoded = InkProblemReport.fromWireJson(report.toWireJson())

        assertFalse(decoded.hasErrors)
        assertEquals(report, decoded.value)
        assertTrue(InkWireValidator.validateProblemReport(decoded.value!!).isEmpty())
    }

    @Test
    fun `unknown document node change and problem fields are rejected`() {
        val document = JSONObject(validDocumentJson()).put("future", true)
        assertDecodeProblem(
            RenderDocument.fromWireJson(document.toString()),
            InkProblemCodes.WIRE_UNKNOWN_FIELD,
            "$.future",
        )

        val nested = JSONObject(validDocumentJson())
        nested.getJSONArray("roots").getJSONObject(0).put("future", true)
        assertDecodeProblem(
            RenderDocument.fromWireJson(nested.toString()),
            InkProblemCodes.WIRE_UNKNOWN_FIELD,
            "$.roots[0].future",
        )

        val patch = JSONObject(validPatchJson())
        patch.getJSONArray("changes").getJSONObject(0).put("future", true)
        assertDecodeProblem(
            RenderPatch.fromWireJson(patch.toString()),
            InkProblemCodes.WIRE_UNKNOWN_FIELD,
            "$.changes[0].future",
        )

        val report = JSONObject("""{"v":1,"problems":[{"code":"X","message":"x","severity":"error","future":1}]}""")
        assertDecodeProblem(
            InkProblemReport.fromWireJson(report.toString()),
            InkProblemCodes.WIRE_UNKNOWN_FIELD,
            "$.problems[0].future",
        )
    }

    @Test
    fun `wire decoders reject coercible but wrong JSON types`() {
        val wrongVersion = JSONObject(validDocumentJson()).put("v", "1")
        assertDecodeProblem(RenderDocument.fromWireJson(wrongVersion.toString()), InkProblemCodes.WIRE_TYPE, "$.v")

        val wrongStyle = JSONObject(validDocumentJson())
        wrongStyle.getJSONArray("roots").getJSONObject(0).put("s", JSONObject().put("width", 10))
        assertDecodeProblem(
            RenderDocument.fromWireJson(wrongStyle.toString()),
            InkProblemCodes.WIRE_TYPE,
            "$.roots[0].s.width",
        )

        val wrongAction = JSONObject(validDocumentJson())
        wrongAction.getJSONArray("roots").getJSONObject(0).put(
            "e",
            JSONObject().put("tap", JSONObject().put("catch", "false").put("id", "open")),
        )
        assertDecodeProblem(
            RenderDocument.fromWireJson(wrongAction.toString()),
            InkProblemCodes.WIRE_TYPE,
            "$.roots[0].e.tap.catch",
        )
    }

    @Test
    fun `decoder rejects oversized wire before JSON parsing`() {
        val oversized = "{\"v\":1,\"padding\":\"${"x".repeat(InkWireLimits.MAX_DOCUMENT_BYTES)}\"}"
        val result = RenderDocument.fromWireJson(oversized)
        assertNull(result.value)
        assertEquals(InkProblemCodes.BUDGET_SIZE, result.problems.single().code)
    }

    @Test
    fun `validator rejects version duplicate ids node count and depth`() {
        val duplicate = RenderNode("same", "view")
        val invalid = RenderDocument(
            roots = listOf(duplicate.copy(children = listOf(duplicate))),
            documentId = "doc",
            version = 2,
        )
        val invalidProblems = InkWireValidator.validateDocument(invalid)
        assertTrue(invalidProblems.any { it.code == InkProblemCodes.WIRE_VERSION })
        assertTrue(invalidProblems.any { it.code == InkProblemCodes.WIRE_ID && it.message.contains("Duplicate") })

        val tooMany = RenderDocument(
            roots = List(InkWireLimits.MAX_NODES + 1) { RenderNode("n$it", "view") },
            documentId = "doc",
        )
        assertTrue(InkWireValidator.validateDocument(tooMany).any { it.code == InkProblemCodes.BUDGET_NODES })

        var deep = RenderNode("leaf", "view")
        repeat(InkWireLimits.MAX_DEPTH) { index -> deep = RenderNode("depth$index", "view", children = listOf(deep)) }
        val deepDocument = RenderDocument(listOf(deep), documentId = "doc")
        assertTrue(InkWireValidator.validateDocument(deepDocument).any { it.code == InkProblemCodes.BUDGET_DEPTH })
    }

    @Test
    fun `validator rejects unsupported styles attributes and oversized actions datasets`() {
        val node = RenderNode(
            id = "node",
            type = "view",
            attributes = mapOf("src" to "not-allowed.png"),
            style = mapOf("background" to "red"),
            events = mapOf("tap" to InkActionBinding("x".repeat(InkWireLimits.MAX_ACTION_ID_CHARS + 1), false)),
            dataset = mapOf("x".repeat(InkWireLimits.MAX_DATASET_KEY_CHARS + 1) to "value"),
        )
        val problems = InkWireValidator.validateDocument(RenderDocument(listOf(node), documentId = "doc"))

        assertTrue(problems.any { it.code == InkProblemCodes.ATTRIBUTE_UNSUPPORTED })
        assertTrue(problems.any { it.code == InkProblemCodes.STYLE_UNSUPPORTED })
        assertTrue(problems.any { it.code == InkProblemCodes.WIRE_ACTION })
        assertTrue(problems.any { it.code == InkProblemCodes.WIRE_DATASET })
    }

    @Test
    fun `validator accepts literal colors but rejects invalid allowed property values`() {
        val literal = RenderDocument(
            listOf(RenderNode("node", "view", style = mapOf("color" to "#ff00ff"))),
            documentId = "doc",
        )
        assertTrue(InkWireValidator.validateDocument(literal).isEmpty())

        val invalid = literal.copy(roots = listOf(RenderNode("node", "view", style = mapOf("opacity" to "2"))))
        assertTrue(InkWireValidator.validateDocument(invalid).any { it.code == InkProblemCodes.STYLE_UNSUPPORTED })
    }

    @Test
    fun `patch validator rejects revision gaps and mismatched added ids`() {
        val patch = RenderPatch(
            changes = listOf(RenderChange.NodeAdded("expected", null, 0, RenderNode("actual", "view"))),
            documentId = "doc",
            baseRevision = 0,
            targetRevision = 2,
        )
        val problems = InkWireValidator.validatePatch(patch)
        assertTrue(problems.any { it.code == InkProblemCodes.WIRE_REVISION })
        assertTrue(problems.any { it.code == InkProblemCodes.WIRE_ID })
    }

    private fun validDocumentJson(): String =
        """{"doc":"doc","rev":0,"roots":[{"id":"root","t":"view"}],"v":1}"""

    private fun validPatchJson(): String =
        """{"baseRev":0,"changes":[{"id":"root","op":"text","value":"next"}],"doc":"doc","targetRev":1,"v":1}"""

    private fun <T> assertDecodeProblem(result: InkWireDecodeResult<T>, code: String, feature: String) {
        assertNull(result.value)
        assertTrue(result.problems.any { it.code == code && it.feature == feature })
    }
}
