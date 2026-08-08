package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionTest {
    @Test
    fun `evaluates literals paths unary arithmetic comparisons logical and ternary`() {
        val data = JSONObject(
            """{"a":3,"b":4,"name":"Ada","items":[{"value":7}],"which":0,"truthy":"yes"}""",
        )
        val cases = mapOf(
            "1 + 2 * 3" to 7.0,
            "-a + b" to 1.0,
            "items[which].value" to 7,
            "a < b && truthy" to "yes",
            "!false" to true,
            "a == '3'" to true,
            "a === 3" to true,
            "a !== '3'" to true,
            "name + ' Lovelace'" to "Ada Lovelace",
            "a > b ? 'large' : 'small'" to "small",
            "null == null" to true,
        )
        cases.forEach { (expression, expected) ->
            val result = InkExpressions.evaluate(expression, data)
            assertEquals("$expression problems=${result.problems}", expected, result.value)
            assertTrue(result.problems.isEmpty())
        }
        assertEquals(setOf("which", "items[0].value"), InkExpressions.evaluate("items[which].value", data).readPaths)
    }

    @Test
    fun `rejects calls assignments and invalid syntax`() {
        listOf("fn()", "a = 1", "a +", "(a").forEach { expression ->
            val result = InkExpressions.evaluate(expression, JSONObject("""{"a":1,"fn":"x"}"""))
            assertEquals(expression, InkProblemCodes.EXPR_INVALID, result.problems.single().code)
        }
    }

    @Test
    fun `does not expose prototype properties`() {
        val result = InkExpressions.evaluate("safe.constructor.secret", JSONObject("""{"safe":{"constructor":{"secret":"no"}}}"""))
        assertEquals(null, result.value)
    }

    @Test
    fun `enforces AST depth limit`() {
        val result = InkExpressions.evaluate("!".repeat(33) + "true", JSONObject())
        assertEquals(InkProblemCodes.EXPR_LIMIT, result.problems.single().code)
    }

    @Test
    fun `enforces evaluation step limit`() {
        val expression = "a" + ".a".repeat(10_001)
        val result = InkExpressions.evaluate(expression, JSONObject("""{"a":{}}"""))
        assertEquals(InkProblemCodes.EXPR_LIMIT, result.problems.single().code)
    }
}
