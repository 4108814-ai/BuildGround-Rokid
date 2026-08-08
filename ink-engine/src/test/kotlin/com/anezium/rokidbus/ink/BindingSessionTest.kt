package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class BindingSessionTest {
    @Test
    fun `top-level setData keys replace objects`() {
        val result = compilePage(
            "<view><text>{{user.name}}/{{user.age}}</text></view>",
            data = JSONObject("""{"user":{"name":"A","age":9}}"""),
        )
        val patch = result.session!!.applyPatch(JSONObject("""{"user":{"name":"B"}}"""))

        assertFalse(patch.hasErrors)
        assertEquals("B/", patch.document!!.roots.single().renderedText())
        assertTrue(patch.patch!!.changes.single() is RenderChange.TextChanged)
    }

    @Test
    fun `setData path keys update nested objects and array indexes`() {
        val result = compilePage(
            "<view><text>{{profile.name}}/{{items[1].title}}</text></view>",
            data = JSONObject("""{"profile":{"name":"A"},"items":[{"title":"zero"},{"title":"one"}]}"""),
        )
        val update = JSONObject()
            .put("profile.name", "B")
            .put("items[1].title", "changed")

        val patch = result.session!!.applyPatch(update)

        assertEquals("B/changed", patch.document!!.roots.single().renderedText())
        assertEquals(1, patch.patch!!.changes.size)
    }

    @Test
    fun `dirty paths do not re-evaluate unrelated bindings`() {
        val result = compilePage(
            "<view><text>{{a}}</text><text>{{b}}</text></view>",
            data = JSONObject("""{"a":"A","b":"B","c":"C"}"""),
        )

        val changed = result.session!!.applyPatch(JSONObject().put("a", "AA"))
        assertEquals(1, changed.evaluatedExpressionCount)
        assertEquals(1, changed.patch!!.changes.size)
        val textChange = changed.patch!!.changes.single() as RenderChange.TextChanged
        assertEquals("AA", textChange.value)

        val unrelated = result.session.applyPatch(JSONObject().put("c", "CC"))
        assertEquals(0, unrelated.evaluatedExpressionCount)
        assertTrue(unrelated.patch!!.changes.isEmpty())
    }

    @Test
    fun `keyed loops retain node identity and emit add move remove changes`() {
        val result = compilePage(
            """
                <view>
                  <view wx:for="{{items}}" wx:for-item="row" wx:for-index="position" wx:key="id">
                    <text>{{position}}:{{row.name}}</text>
                  </view>
                </view>
            """,
            data = JSONObject("""{"items":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}"""),
        )
        val oldIds = result.document!!.roots.single().children.map { it.id }
        val replacement = JSONArray()
            .put(JSONObject("""{"id":"b","name":"Bee"}"""))
            .put(JSONObject("""{"id":"a","name":"A"}"""))
            .put(JSONObject("""{"id":"c","name":"C"}"""))

        val firstPatch = result.session!!.applyPatch(JSONObject().put("items", replacement))
        val newChildren = firstPatch.document!!.roots.single().children
        assertEquals(oldIds[1], newChildren[0].id)
        assertEquals(oldIds[0], newChildren[1].id)
        assertTrue(firstPatch.patch!!.changes.any { it is RenderChange.NodeMoved && it.nodeId == oldIds[1] })
        assertTrue(firstPatch.patch.changes.any { it is RenderChange.NodeAdded && it.nodeId == newChildren[2].id })
        assertTrue(firstPatch.patch.changes.any { it is RenderChange.TextChanged && it.value == "0:Bee" })

        val secondPatch = result.session.applyPatch(
            JSONObject().put("items", JSONArray().put(JSONObject("""{"id":"b","name":"Bee"}"""))),
        )
        assertTrue(secondPatch.patch!!.changes.any { it is RenderChange.NodeRemoved && it.nodeId == oldIds[0] })
    }

    @Test
    fun `conditional updates add and remove stable branch nodes`() {
        val result = compilePage(
            "<view><text wx:if=\"{{visible}}\">yes</text><text wx:else>no</text></view>",
            data = JSONObject().put("visible", false),
        )
        val oldId = result.document!!.roots.single().children.single().id

        val patch = result.session!!.applyPatch(JSONObject().put("visible", true))

        val newId = patch.document!!.roots.single().children.single().id
        assertNotEquals(oldId, newId)
        assertTrue(patch.patch!!.changes.any { it is RenderChange.NodeRemoved && it.nodeId == oldId })
        assertTrue(patch.patch.changes.any { it is RenderChange.NodeAdded && it.nodeId == newId })
    }

    @Test
    fun `bound attributes styles and datasets produce typed changes`() {
        val result = compilePage(
            "<image src=\"{{src}}\" class=\"{{tone}}\" data-index=\"{{index}}\" />",
            ".one { opacity: 1; } .two { opacity: 0.5; }",
            JSONObject("""{"src":"a.png","tone":"one","index":0}"""),
        )

        val patch = result.session!!.applyPatch(
            JSONObject().put("src", "b.png").put("tone", "two").put("index", 1),
        )

        assertTrue(patch.patch!!.changes.any { it is RenderChange.AttributeChanged && it.name == "src" && it.value == "b.png" })
        assertTrue(patch.patch.changes.any { it is RenderChange.StyleChanged && it.name == "opacity" && it.value == "0.5" })
        assertTrue(patch.patch.changes.any { it is RenderChange.DatasetChanged && it.name == "index" && it.value == 1 })
    }

    @Test
    fun `nested loops use configured item and index names`() {
        val page = """
            <view wx:for="{{groups}}" wx:for-item="group" wx:key="id">
              <text wx:for="{{group.values}}" wx:for-item="value" wx:for-index="i" wx:key="*this">{{i}}={{value}}</text>
            </view>
        """
        val result = compilePage(page, data = JSONObject("""{"groups":[{"id":"g","values":["x","y"]}]}"""))
        assertEquals("0=x1=y", result.requireDocument().roots.single().renderedText())
    }

    @Test
    fun `patch budget failure leaves session state unchanged`() {
        val result = compilePage("<text>{{value}}</text>", data = JSONObject().put("value", "small"))
        val before = result.document!!.toWireJson()

        val patch = result.session!!.applyPatch(JSONObject().put("value", "x".repeat(17_000)))

        assertEquals(InkProblemCodes.BUDGET_SIZE, patch.problems.single().code)
        assertEquals(before, result.session.document.toWireJson())
    }

    @Test
    fun `wire budget failure leaves session state unchanged`() {
        val result = compilePage(
            """
                <view>
                  <view wx:for="{{items}}" wx:key="id"><text>{{item.value}}</text></view>
                </view>
            """,
            data = JSONObject().put("items", JSONArray()),
        )
        val before = result.document!!.toWireJson()
        val items = JSONArray().apply {
            repeat(130) { index ->
                put(JSONObject().put("id", index.toString()).put("value", "x"))
            }
        }

        val patch = result.session!!.applyPatch(JSONObject().put("items", items))

        assertTrue(patch.patch == null)
        assertTrue(patch.problems.any { it.code == InkProblemCodes.BUDGET_NODES })
        assertEquals(before, result.session.document.toWireJson())
    }

    @Test
    fun `sessions reject mutation from another thread`() {
        val result = compilePage("<text>{{value}}</text>", data = JSONObject().put("value", "a"))
        val otherThreadResult = AtomicReference<InkPatchResult>()
        val thread = Thread {
            otherThreadResult.set(result.session!!.applyPatch(JSONObject().put("value", "b")))
        }
        thread.start()
        thread.join()

        assertEquals(InkProblemCodes.THREAD_INVALID, otherThreadResult.get().problems.single().code)
        assertEquals("a", result.session!!.document.roots.single().renderedText())
    }
}
