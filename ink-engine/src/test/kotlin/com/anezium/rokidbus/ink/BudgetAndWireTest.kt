package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAndWireTest {
    @Test
    fun `rejects page size over 32 KiB`() {
        val page = "<text>${"x".repeat(InkEngine.MAX_PAGE_BYTES)}</text>"
        val result = compilePage(page)
        assertTrue(result.problems.any { it.code == InkProblemCodes.BUDGET_SIZE && it.feature == "page" })
        assertNull(result.document)
    }

    @Test
    fun `rejects data size over 16 KiB`() {
        val result = compilePage("<text>{{value}}</text>", data = JSONObject().put("value", "x".repeat(17_000)))
        assertTrue(result.problems.any { it.code == InkProblemCodes.BUDGET_SIZE && it.feature == "data" })
        assertNull(result.document)
    }

    @Test
    fun `rejects more than 256 expanded nodes`() {
        val items = JSONArray()
        repeat(128) { items.put(it) }
        val result = compilePage(
            "<view><view wx:for=\"{{items}}\"><text>{{item}}</text></view></view>",
            data = JSONObject().put("items", items),
        )
        assertTrue(result.problems.any { it.code == InkProblemCodes.BUDGET_NODES })
        assertNull(result.document)
    }

    @Test
    fun `expression limits surface through compilation`() {
        val depth = compilePage("<text>{{${"!".repeat(33)}true}}</text>")
        assertTrue(depth.problems.any { it.code == InkProblemCodes.EXPR_LIMIT })

        val longPath = "a" + ".a".repeat(10_001)
        val steps = compilePage("<text>{{$longPath}}</text>", data = JSONObject("""{"a":{}}"""))
        assertTrue(steps.problems.any { it.code == InkProblemCodes.EXPR_LIMIT })
    }

    @Test
    fun `document patch and problem JSON are deterministic v1`() {
        assertEquals("INK_DOC_V1", InkWire.DOCUMENT_CONTRACT)
        val result = compilePage(
            "<view id=\"root\"><text>{{message}}</text></view>",
            ".unused { width: 1px; }",
            JSONObject().put("message", "hello"),
        )
        val document = result.requireDocument()
        val first = document.toWireJson()
        val second = document.toWireJson()
        assertEquals(first, second)
        val documentJson = JSONObject(first)
        assertEquals(1, documentJson.getInt("v"))
        assertEquals(0, documentJson.getInt("rev"))
        assertEquals(document.documentId, documentJson.getString("doc"))
        assertTrue(first.indexOf("\"roots\"") < first.indexOf("\"v\""))

        val update = result.session!!.applyPatch(JSONObject().put("message", "world"))
        val patchJson = update.patch!!.toWireJson()
        assertEquals(patchJson, update.patch.toWireJson())
        val patchObject = JSONObject(patchJson)
        assertEquals(1, patchObject.getInt("v"))
        assertEquals(document.documentId, patchObject.getString("doc"))
        assertEquals(0, patchObject.getInt("baseRev"))
        assertEquals(1, patchObject.getInt("targetRev"))
        assertNotNull(patchObject.getJSONArray("changes"))

        val report = InkProblemReport(
            listOf(
                InkProblem(
                    InkProblemCodes.COMPONENT_UNSUPPORTED,
                    "unsupported",
                    line = 2,
                    column = 3,
                    feature = "button",
                ),
            ),
        ).toWireJson()
        assertEquals(report, InkProblemReport(listOf(InkProblem(InkProblemCodes.COMPONENT_UNSUPPORTED, "unsupported", line = 2, column = 3, feature = "button"))).toWireJson())
        val problem = JSONObject(report).getJSONArray("problems").getJSONObject(0)
        assertEquals("INK_COMPONENT_UNSUPPORTED", problem.getString("code"))
        assertEquals(3, problem.getInt("col"))
    }
}
