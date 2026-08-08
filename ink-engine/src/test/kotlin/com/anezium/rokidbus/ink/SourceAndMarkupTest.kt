package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAndMarkupTest {
    @Test
    fun `SFC splits documented blocks and deep merges host data`() {
        val source = """
            <script type="application/json" def>
            {
              "navigationBarTitleText": "Home",
              "data": {"profile":{"name":"Default","prefs":{"left":1,"right":2}}}
            }
            </script>
            <page>
              <view class="container"><text>{{profile.name}}/{{profile.prefs.left}}/{{profile.prefs.right}}</text></view>
            </page>
            <style>.container { display: flex; flex-direction: column; }</style>
        """.trimIndent()
        val host = JSONObject("""{"profile":{"name":"Host","prefs":{"right":3}}}""")

        val result = InkEngine.compile(InkSource.Sfc(source), host)

        assertFalse(result.hasErrors)
        val document = result.requireDocument()
        assertEquals("Home", document.metadata["navigationBarTitleText"])
        assertEquals("Host/1/3", document.roots.single().renderedText())
    }

    @Test
    fun `SFC rejects script setup without hiding other valid blocks`() {
        val result = InkEngine.compile(
            InkSource.Sfc(
                """
                    <script def>{}</script>
                    <script setup>export default {}</script>
                    <page><view /></page>
                """.trimIndent(),
            ),
        )

        assertNull(result.document)
        assertEquals(listOf(InkProblemCodes.SCRIPT_UNSUPPORTED), result.problems.map { it.code })
        assertEquals("script setup", result.problems.single().feature)
    }

    @Test
    fun `SFC reports required and unknown blocks`() {
        val missing = InkEngine.compile(InkSource.Sfc("<style>.a { width: 1px; }</style>"))
        assertEquals(
            listOf("script def", "page"),
            missing.problems.filter { it.code == InkProblemCodes.BLOCK_REQUIRED }.map { it.feature },
        )

        val unknown = InkEngine.compile(
            InkSource.Sfc("<script def>{}</script><widget></widget><page><view /></page>"),
        )
        assertTrue(unknown.problems.any { it.code == InkProblemCodes.BLOCK_UNKNOWN && it.feature == "widget" })
    }

    @Test
    fun `invalid definition is typed`() {
        val result = InkEngine.compile(InkSource.Sfc("<script def>{bad}</script><page><view /></page>"))
        assertTrue(result.problems.any { it.code == InkProblemCodes.DEFINITION_INVALID && it.line != null })
    }

    @Test
    fun `markup supports mixed bindings attributes actions and datasets`() {
        val result = compilePage(
            """
                <scroll-view id="main" class="panel {{tone}}" style="width: {{width}}px" scroll-y="true"
                    bindtap="openDetail" catchlongpress="hold" data-row="{{row}}">
                  <image src="assets/{{image}}" mode="widthFix" />
                  <text>Hello {{name}} #{{row + 1}}</text>
                </scroll-view>
            """,
            ".panel { display: flex; } .active { color: var(--color-primary); }",
            JSONObject("""{"tone":"active","width":120,"row":7,"image":"logo.png","name":"Ada"}"""),
        )

        assertFalse(result.hasErrors)
        val root = result.requireDocument().roots.single()
        assertEquals("main", root.attributes["id"])
        assertEquals(true, root.attributes["scroll-y"])
        assertEquals("120px", root.style["width"])
        assertEquals(InkActionBinding("openDetail", false), root.events["tap"])
        assertEquals(InkActionBinding("hold", true), root.events["longpress"])
        assertEquals(7, root.dataset["row"])
        assertEquals("assets/logo.png", root.children[0].attributes["src"])
        assertEquals("Hello Ada #8", root.children[1].renderedText())
    }

    @Test
    fun `conditional chains choose one branch for wx and ink aliases`() {
        val page = """
            <view>
              <text wx:if="{{state === 1}}">one</text>
              <text wx:elif="{{state === 2}}">two</text>
              <text wx:else>other</text>
              <text ink:if="{{shown}}">shown</text>
            </view>
        """
        val result = compilePage(page, data = JSONObject("""{"state":2,"shown":true}"""))
        assertEquals("twoshown", result.requireDocument().roots.single().renderedText())
    }

    @Test
    fun `orphan elif and malformed nesting have markup locations`() {
        val orphan = compilePage("<view><text wx:elif=\"{{ok}}\">bad</text></view>")
        val orphanProblem = orphan.problems.single { it.code == InkProblemCodes.MARKUP_INVALID }
        assertNotNull(orphanProblem.line)
        assertNotNull(orphanProblem.column)

        val nested = compilePage("<view>\n<text>bad</view></text>")
        val nestingProblem = nested.problems.single { it.code == InkProblemCodes.MARKUP_INVALID }
        assertEquals(2, nestingProblem.line)
        assertTrue(nestingProblem.column!! > 0)
    }

    @Test
    fun `condition combined with loop on one element is rejected`() {
        val result = compilePage(
            "<view><text wx:for=\"{{items}}\" wx:if=\"{{item.visible}}\" wx:key=\"*this\">x</text></view>",
            data = JSONObject("""{"items":["a"]}"""),
        )
        assertTrue(result.problems.any { it.code == InkProblemCodes.MARKUP_INVALID && it.feature == "if+for" })
    }

    @Test
    fun `unsupported component and attribute are typed by feature`() {
        val result = compilePage("<button mystery=\"x\">tap</button>")
        assertTrue(result.problems.any { it.code == InkProblemCodes.COMPONENT_UNSUPPORTED && it.feature == "button" })
        assertTrue(result.problems.any { it.code == InkProblemCodes.ATTRIBUTE_UNSUPPORTED && it.feature == "mystery" })
    }

    @Test
    fun `bad attribute syntax is markup invalid`() {
        val result = compilePage("<view class=unquoted />")
        assertTrue(result.problems.any { it.code == InkProblemCodes.MARKUP_INVALID && it.line == 1 })
    }

    @Test
    fun `definition data must be an object`() {
        val result = InkEngine.compile(InkSource.Sfc("<script def>{\"data\":[]}</script><page><view /></page>"))
        assertTrue(result.problems.any { it.code == InkProblemCodes.DEFINITION_INVALID && it.feature == "data" })
    }
}
