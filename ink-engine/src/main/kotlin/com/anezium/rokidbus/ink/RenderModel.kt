package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject

object InkWire {
    const val VERSION = 1
    const val DOCUMENT_CONTRACT = "INK_DOC_V1"
}

data class InkActionBinding(
    val actionId: String,
    val catches: Boolean,
)

data class RenderNode(
    val id: String,
    val type: String,
    val text: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val style: Map<String, String> = emptyMap(),
    val events: Map<String, InkActionBinding> = emptyMap(),
    val dataset: Map<String, Any?> = emptyMap(),
    val children: List<RenderNode> = emptyList(),
) {
    internal fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("t", type)
        text?.let { put("x", it) }
        if (attributes.isNotEmpty()) put("a", attributes.toJsonValue())
        if (style.isNotEmpty()) put("s", style.toJsonValue())
        if (events.isNotEmpty()) {
            put("e", JSONObject().also { json ->
                events.toSortedMap().forEach { (event, action) ->
                    json.put(
                        event,
                        JSONObject().apply {
                            put("catch", action.catches)
                            put("id", action.actionId)
                        },
                    )
                }
            })
        }
        if (dataset.isNotEmpty()) put("d", dataset.toJsonValue())
        if (children.isNotEmpty()) put("c", JSONArray().also { array -> children.forEach { array.put(it.toJsonObject()) } })
    }
}

data class RenderDocument(
    val roots: List<RenderNode>,
    val metadata: Map<String, Any?> = emptyMap(),
    val version: Int = InkWire.VERSION,
) {
    val nodeCount: Int
        get() = roots.sumOf(RenderNode::countNodes)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("v", version)
        if (metadata.isNotEmpty()) put("meta", metadata.toJsonValue())
        put("roots", JSONArray().also { array -> roots.forEach { array.put(it.toJsonObject()) } })
    }

    fun toWireJson(): String = DeterministicJson.stringify(toJsonObject())
}

private fun RenderNode.countNodes(): Int = 1 + children.sumOf(RenderNode::countNodes)

sealed interface RenderChange {
    val nodeId: String

    data class NodeAdded(
        override val nodeId: String,
        val parentId: String?,
        val index: Int,
        val node: RenderNode,
    ) : RenderChange

    data class NodeRemoved(
        override val nodeId: String,
        val parentId: String?,
        val index: Int,
    ) : RenderChange

    data class NodeMoved(
        override val nodeId: String,
        val parentId: String?,
        val fromIndex: Int,
        val toIndex: Int,
    ) : RenderChange

    data class TextChanged(override val nodeId: String, val value: String) : RenderChange
    data class AttributeChanged(override val nodeId: String, val name: String, val value: Any?) : RenderChange
    data class StyleChanged(override val nodeId: String, val name: String, val value: String?) : RenderChange
    data class EventChanged(override val nodeId: String, val name: String, val value: InkActionBinding?) : RenderChange
    data class DatasetChanged(override val nodeId: String, val name: String, val value: Any?) : RenderChange
}

data class RenderPatch(
    val changes: List<RenderChange>,
    val version: Int = InkWire.VERSION,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("v", version)
        put("changes", JSONArray().also { array -> changes.forEach { array.put(it.toJsonObject()) } })
    }

    fun toWireJson(): String = DeterministicJson.stringify(toJsonObject())
}

private fun RenderChange.toJsonObject(): JSONObject = JSONObject().apply {
    put("id", nodeId)
    when (this@toJsonObject) {
        is RenderChange.NodeAdded -> {
            put("op", "add")
            put("parent", parentId ?: JSONObject.NULL)
            put("index", index)
            put("node", node.toJsonObject())
        }
        is RenderChange.NodeRemoved -> {
            put("op", "remove")
            put("parent", parentId ?: JSONObject.NULL)
            put("index", index)
        }
        is RenderChange.NodeMoved -> {
            put("op", "move")
            put("parent", parentId ?: JSONObject.NULL)
            put("from", fromIndex)
            put("to", toIndex)
        }
        is RenderChange.TextChanged -> {
            put("op", "text")
            put("value", value)
        }
        is RenderChange.AttributeChanged -> {
            put("op", "attr")
            put("name", name)
            put("value", value.toJsonValue())
        }
        is RenderChange.StyleChanged -> {
            put("op", "style")
            put("name", name)
            put("value", value ?: JSONObject.NULL)
        }
        is RenderChange.EventChanged -> {
            put("op", "event")
            put("name", name)
            put(
                "value",
                value?.let {
                    JSONObject().apply {
                        put("catch", it.catches)
                        put("id", it.actionId)
                    }
                } ?: JSONObject.NULL,
            )
        }
        is RenderChange.DatasetChanged -> {
            put("op", "dataset")
            put("name", name)
            put("value", value.toJsonValue())
        }
    }
}

internal object RenderDiffer {
    fun diff(old: RenderDocument, new: RenderDocument): RenderPatch {
        val changes = mutableListOf<RenderChange>()
        diffChildren(null, old.roots, new.roots, changes)
        return RenderPatch(changes)
    }

    private fun diffChildren(
        parentId: String?,
        old: List<RenderNode>,
        new: List<RenderNode>,
        changes: MutableList<RenderChange>,
    ) {
        val oldById = old.associateBy { it.id }
        val newById = new.associateBy { it.id }
        val working = old.mapTo(mutableListOf()) { it.id }

        for (oldIndex in old.indices.reversed()) {
            val node = old[oldIndex]
            if (node.id !in newById) {
                changes += RenderChange.NodeRemoved(node.id, parentId, oldIndex)
                working.removeAt(oldIndex)
            }
        }
        new.forEachIndexed { newIndex, node ->
            val currentIndex = working.indexOf(node.id)
            if (currentIndex < 0) {
                changes += RenderChange.NodeAdded(node.id, parentId, newIndex, node)
                working.add(newIndex.coerceAtMost(working.size), node.id)
            } else if (currentIndex != newIndex) {
                changes += RenderChange.NodeMoved(node.id, parentId, currentIndex, newIndex)
                working.removeAt(currentIndex)
                working.add(newIndex, node.id)
            }
        }

        new.forEach { newNode ->
            val oldNode = oldById[newNode.id] ?: return@forEach
            diffNode(oldNode, newNode, changes)
        }
    }

    private fun diffNode(old: RenderNode, new: RenderNode, changes: MutableList<RenderChange>) {
        if (old.text != new.text && new.text != null) changes += RenderChange.TextChanged(new.id, new.text)
        diffMap(old.attributes, new.attributes) { name, value ->
            changes += RenderChange.AttributeChanged(new.id, name, value)
        }
        diffMap(old.style, new.style) { name, value ->
            changes += RenderChange.StyleChanged(new.id, name, value)
        }
        diffMap(old.events, new.events) { name, value ->
            changes += RenderChange.EventChanged(new.id, name, value)
        }
        diffMap(old.dataset, new.dataset) { name, value ->
            changes += RenderChange.DatasetChanged(new.id, name, value)
        }
        diffChildren(new.id, old.children, new.children, changes)
    }

    private fun <T> diffMap(old: Map<String, T>, new: Map<String, T>, changed: (String, T?) -> Unit) {
        (old.keys + new.keys).toSortedSet().forEach { key ->
            if (old[key] != new[key] || old.containsKey(key) != new.containsKey(key)) changed(key, new[key])
        }
    }
}
