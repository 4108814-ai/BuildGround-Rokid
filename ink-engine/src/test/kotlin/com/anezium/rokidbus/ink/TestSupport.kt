package com.anezium.rokidbus.ink

import org.json.JSONObject
import org.junit.Assert.assertNotNull

internal fun compilePage(
    wxml: String,
    wxss: String = "",
    data: JSONObject = JSONObject(),
): InkCompileResult = InkEngine.compile(InkSource.MultiFile(wxml.trimIndent(), wxss.trimIndent()), data)

internal fun InkCompileResult.requireDocument(): RenderDocument {
    assertNotNull("Compile failed: $problems", document)
    return document!!
}

internal fun RenderDocument.allNodes(): List<RenderNode> = buildList {
    fun addTree(node: RenderNode) {
        add(node)
        node.children.forEach(::addTree)
    }
    roots.forEach(::addTree)
}

internal fun RenderNode.renderedText(): String = buildString {
    text?.let(::append)
    children.forEach { append(it.renderedText()) }
}
