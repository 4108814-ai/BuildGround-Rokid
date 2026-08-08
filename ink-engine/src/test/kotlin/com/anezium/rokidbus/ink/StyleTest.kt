package com.anezium.rokidbus.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {
    @Test
    fun `parses v1 property groups and typed values`() {
        val style = """
            .all {
              --accent: rgb(64, 255, 94);
              display: flex;
              flex-direction: column;
              flex-wrap: wrap;
              justify-content: space-between;
              align-items: center;
              align-self: stretch;
              flex: 1 0 20%;
              flex-grow: 2;
              flex-shrink: 0;
              flex-basis: 24rpx;
              gap: 8px;
              width: 100%; min-height: 20px; max-width: 400rpx;
              margin: 1px 2px 3px 4px; padding-left: 5px; box-sizing: border-box;
              border: 1px solid var(--accent); border-radius: 8px;
              font-size: 18px; font-weight: 600; line-height: 24px;
              text-align: center; text-overflow: ellipsis; white-space: nowrap;
              opacity: 0.8; color: var(--accent); background-color: rgba(0, 0, 0, 0.5);
              transform: translate(1px, 2px) scale(1.2) rotate(2deg);
              transition: opacity 200ms ease 20ms;
              position: absolute; top: 1px; right: 2px; bottom: 3px; left: 4px;
              overflow: hidden;
            }
        """.trimIndent()
        val parsed = InkStyles.parse(style)

        assertFalse(parsed.problems.any { it.code == InkProblemCodes.STYLE_UNSUPPORTED })
        val declarations = parsed.rules.single().declarations
        assertEquals(InkStyleValueKind.NUMBER, declarations["width"]?.kind)
        assertEquals(InkStyleToken.Dimension(100.0, "%"), declarations["width"]?.tokens?.single())
        assertEquals(InkStyleValueKind.TRANSFORM, declarations["transform"]?.kind)
        assertTrue(declarations["transform"]?.tokens?.first() is InkStyleToken.Function)
        assertEquals(InkStyleValueKind.TRANSITION, declarations["transition"]?.kind)
        assertTrue(parsed.problems.any { it.code == InkProblemCodes.COLOR_LITERAL })
    }

    @Test
    fun `rejects every non-class selector category`() {
        val selectors = listOf("view", "#main", ".a .b", ".a > .b", ".a:hover", ".a, .b")
        selectors.forEach { selector ->
            val result = InkStyles.parse("$selector { width: 1px; }")
            val problem = result.problems.single()
            assertEquals(selector, InkProblemCodes.SELECTOR_UNSUPPORTED, problem.code)
            assertEquals(selector, problem.feature)
            assertEquals(InkProblemSeverity.ERROR, problem.severity)
        }
    }

    @Test
    fun `collects unsupported and explicitly excluded declarations`() {
        val result = InkStyles.parse(
            """
                .box {
                  filter: blur(2px);
                  animation-name: pulse;
                  position: fixed;
                  visibility: hidden;
                  word-break: break-all;
                }
                .sticky { position: sticky; }
                @keyframes pulse { from { opacity: 0; } to { opacity: 1; } }
            """.trimIndent(),
        )

        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_UNSUPPORTED && it.feature == "filter" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_UNSUPPORTED && it.feature == "position" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_EXCLUDED && it.feature == "animation-name" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_EXCLUDED && it.feature == "visibility" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_EXCLUDED && it.feature == "word-break" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_EXCLUDED && it.feature == "position" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.STYLE_EXCLUDED && it.feature == "@keyframes" })
        assertTrue(result.problems.all { it.severity == InkProblemSeverity.WARNING })
    }

    @Test
    fun `cascades classes in source order resolves variables and inline style`() {
        val result = compilePage(
            "<view class=\"base selected\" style=\"width: 30px\"><text>child</text></view>",
            """
                .base { --space: 10px; width: var(--space); color: var(--color-primary); }
                .selected { width: 20px; text-align: right; }
            """,
        )
        val root = result.requireDocument().roots.single()
        assertEquals("30px", root.style["width"])
        assertEquals("right", root.children.single().style["text-align"])
        assertEquals("var(--color-primary)", root.children.single().style["color"])
    }
}
