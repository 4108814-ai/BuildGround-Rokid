package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.ink.InkWireValidator
import com.anezium.rokidbus.ink.RenderChange
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import com.anezium.rokidbus.ink.RenderPatch

internal class InkNodeStore private constructor(
    val documentId: String,
    val revision: Int,
    val version: Int,
    private val metadata: Map<String, Any?>,
    private val roots: MutableList<StoredNode>,
) {
    private data class Entry(val node: StoredNode, val parentId: String?)

    internal data class StoredNode(
        val id: String,
        val type: String,
        var text: String?,
        val attributes: MutableMap<String, Any?>,
        val style: MutableMap<String, String>,
        val events: MutableMap<String, com.anezium.rokidbus.ink.InkActionBinding>,
        val dataset: MutableMap<String, Any?>,
        val children: MutableList<StoredNode>,
    )

    private val index = linkedMapOf<String, Entry>()

    init {
        roots.forEach { indexTree(it, null) }
    }

    fun document(): RenderDocument = RenderDocument(
        roots = roots.map(StoredNode::snapshot),
        metadata = deepCopyMap(metadata),
        documentId = documentId,
        revision = revision,
        version = version,
    )

    fun node(nodeId: String): RenderNode? = index[nodeId]?.node?.snapshot()

    fun parentId(nodeId: String): String? = index[nodeId]?.parentId

    fun childIds(parentId: String?): List<String> = children(parentId)?.map { it.id }.orEmpty()

    fun rootNodes(): List<RenderNode> = roots.map(StoredNode::snapshot)

    fun contains(nodeId: String): Boolean = nodeId in index

    private fun trialAtRevision(targetRevision: Int): InkNodeStore = InkNodeStore(
        documentId = documentId,
        revision = targetRevision,
        version = version,
        metadata = deepCopyMap(metadata),
        roots = roots.mapTo(mutableListOf()) { it.snapshot().storedCopy() },
    )

    private fun apply(change: RenderChange): InkProblem? = when (change) {
        is RenderChange.NodeAdded -> add(change)
        is RenderChange.NodeRemoved -> remove(change)
        is RenderChange.NodeMoved -> move(change)
        is RenderChange.TextChanged -> mutate(change.nodeId) { it.text = change.value }
        is RenderChange.AttributeChanged -> mutate(change.nodeId) {
            if (change.value == null) it.attributes.remove(change.name) else it.attributes[change.name] = deepCopy(change.value)
        }
        is RenderChange.StyleChanged -> mutate(change.nodeId) {
            val value = change.value
            if (value == null) it.style.remove(change.name) else it.style[change.name] = value
        }
        is RenderChange.EventChanged -> mutate(change.nodeId) {
            val value = change.value
            if (value == null) it.events.remove(change.name) else it.events[change.name] = value
        }
        is RenderChange.DatasetChanged -> mutate(change.nodeId) {
            if (change.value == null) it.dataset.remove(change.name) else it.dataset[change.name] = deepCopy(change.value)
        }
    }

    private fun add(change: RenderChange.NodeAdded): InkProblem? {
        val siblings = children(change.parentId)
            ?: return invalid("Patch parent '${change.parentId}' does not exist", change.parentId ?: "roots")
        if (change.index !in 0..siblings.size) return invalid("Patch add index ${change.index} is out of bounds", change.nodeId)
        val addedIds = change.node.flattenIds()
        val collision = addedIds.firstOrNull(index::containsKey)
        if (collision != null) return invalid("Patch adds duplicate node id '$collision'", collision)
        val stored = change.node.storedCopy()
        siblings.add(change.index, stored)
        indexTree(stored, change.parentId)
        return null
    }

    private fun remove(change: RenderChange.NodeRemoved): InkProblem? {
        val entry = index[change.nodeId] ?: return invalid("Patch node '${change.nodeId}' does not exist", change.nodeId)
        if (entry.parentId != change.parentId) return invalid("Patch remove parent does not match the node registry", change.nodeId)
        val siblings = children(change.parentId) ?: return invalid("Patch parent does not exist", change.nodeId)
        if (change.index !in siblings.indices || siblings[change.index].id != change.nodeId) {
            return invalid("Patch remove index does not match the node registry", change.nodeId)
        }
        val removed = siblings.removeAt(change.index)
        removed.flattenIds().forEach(index::remove)
        return null
    }

    private fun move(change: RenderChange.NodeMoved): InkProblem? {
        val entry = index[change.nodeId] ?: return invalid("Patch node '${change.nodeId}' does not exist", change.nodeId)
        if (entry.parentId != change.parentId) return invalid("Patch move parent does not match the node registry", change.nodeId)
        val siblings = children(change.parentId) ?: return invalid("Patch parent does not exist", change.nodeId)
        if (change.fromIndex !in siblings.indices || siblings[change.fromIndex].id != change.nodeId) {
            return invalid("Patch move source does not match the node registry", change.nodeId)
        }
        if (change.toIndex !in siblings.indices) return invalid("Patch move target is out of bounds", change.nodeId)
        val node = siblings.removeAt(change.fromIndex)
        siblings.add(change.toIndex, node)
        return null
    }

    private fun mutate(nodeId: String, action: (StoredNode) -> Unit): InkProblem? {
        val node = index[nodeId]?.node ?: return invalid("Patch node '$nodeId' does not exist", nodeId)
        action(node)
        return null
    }

    private fun children(parentId: String?): MutableList<StoredNode>? =
        if (parentId == null) roots else index[parentId]?.node?.children

    private fun indexTree(node: StoredNode, parentId: String?) {
        index[node.id] = Entry(node, parentId)
        node.children.forEach { indexTree(it, node.id) }
    }

    companion object {
        fun from(document: RenderDocument): InkNodeStore = InkNodeStore(
            documentId = document.documentId,
            revision = document.revision,
            version = document.version,
            metadata = deepCopyMap(document.metadata),
            roots = document.roots.mapTo(mutableListOf(), RenderNode::storedCopy),
        )
    }

    internal object Executor {
        fun apply(store: InkNodeStore, patch: RenderPatch): InkPatchApplyResult {
            if (patch.documentId != store.documentId || patch.baseRevision != store.revision) {
                return InkPatchApplyResult.ResyncNeeded(
                    currentDocumentId = store.documentId,
                    currentRevision = store.revision,
                    patchDocumentId = patch.documentId,
                    patchBaseRevision = patch.baseRevision,
                )
            }
            InkWireValidator.validatePatch(patch).firstOrNull()?.let {
                return InkPatchApplyResult.Invalid(it)
            }
            val trial = store.trialAtRevision(patch.targetRevision)
            patch.changes.forEach { change ->
                trial.apply(change)?.let { return InkPatchApplyResult.Invalid(it) }
            }
            InkWireValidator.validateDocument(trial.document()).firstOrNull()?.let {
                return InkPatchApplyResult.Invalid(it)
            }
            return InkPatchApplyResult.Applied(trial)
        }
    }
}

internal sealed interface InkPatchApplyResult {
    data class Applied(val store: InkNodeStore) : InkPatchApplyResult
    data class Invalid(val problem: InkProblem) : InkPatchApplyResult
    data class ResyncNeeded(
        val currentDocumentId: String,
        val currentRevision: Int,
        val patchDocumentId: String,
        val patchBaseRevision: Int,
    ) : InkPatchApplyResult
}

private fun RenderNode.storedCopy(): InkNodeStore.StoredNode = InkNodeStore.StoredNode(
    id = id,
    type = type,
    text = text,
    attributes = attributes.mapValuesTo(linkedMapOf()) { deepCopy(it.value) },
    style = style.toMutableMap(),
    events = events.toMutableMap(),
    dataset = dataset.mapValuesTo(linkedMapOf()) { deepCopy(it.value) },
    children = children.mapTo(mutableListOf(), RenderNode::storedCopy),
)

private fun InkNodeStore.StoredNode.snapshot(): RenderNode = RenderNode(
    id = id,
    type = type,
    text = text,
    attributes = attributes.mapValues { deepCopy(it.value) },
    style = style.toMap(),
    events = events.toMap(),
    dataset = dataset.mapValues { deepCopy(it.value) },
    children = children.map(InkNodeStore.StoredNode::snapshot),
)

private fun RenderNode.flattenIds(): List<String> = buildList {
    add(id)
    children.forEach { addAll(it.flattenIds()) }
}

private fun InkNodeStore.StoredNode.flattenIds(): List<String> = buildList {
    add(id)
    children.forEach { addAll(it.flattenIds()) }
}

private fun deepCopyMap(source: Map<String, Any?>): Map<String, Any?> =
    source.mapValues { deepCopy(it.value) }

private fun deepCopy(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { it.key.toString() to deepCopy(it.value) }
    is List<*> -> value.map(::deepCopy)
    else -> value
}

private fun invalid(message: String, feature: String): InkProblem =
    InkProblem(InkProblemCodes.WIRE_INVALID, message, feature = feature)
