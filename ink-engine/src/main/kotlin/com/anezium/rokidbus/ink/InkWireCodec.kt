package com.anezium.rokidbus.ink

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class InkWireDecodeResult<T>(
    val value: T?,
    val problems: List<InkProblem>,
) {
    val hasErrors: Boolean
        get() = problems.any { it.severity == InkProblemSeverity.ERROR }
}

object InkWireLimits {
    const val MAX_DOCUMENT_BYTES = 64 * 1024
    const val MAX_PATCH_BYTES = 64 * 1024
    const val MAX_PROBLEM_REPORT_BYTES = 16 * 1024
    const val MAX_METADATA_BYTES = 16 * 1024
    const val MAX_NODES = 256
    const val MAX_DEPTH = 32
    const val MAX_CHANGES = 1_024
    const val MAX_DOCUMENT_ID_CHARS = 128
    const val MAX_NODE_ID_CHARS = 96
    const val MAX_TEXT_BYTES = 16 * 1024
    const val MAX_ATTRIBUTES = 32
    const val MAX_STYLES = 64
    const val MAX_STYLE_VALUE_CHARS = 512
    const val MAX_EVENTS = 16
    const val MAX_ACTION_ID_CHARS = 128
    const val MAX_DATASET_ENTRIES = 32
    const val MAX_DATASET_KEY_CHARS = 64
    const val MAX_DATASET_BYTES = 4 * 1024
    const val MAX_JSON_VALUE_DEPTH = 16
    const val MAX_CHART_SERIES = InkComponentContract.MAX_CHART_SERIES
    const val MAX_CHART_POINTS = InkComponentContract.MAX_CHART_POINTS
    const val MAX_CANVAS_COMMANDS = InkComponentContract.MAX_CANVAS_COMMANDS
    const val MAX_LOTTIE_JSON_BYTES = InkComponentContract.MAX_LOTTIE_JSON_BYTES
}

object InkWireCodec {
    fun decodeDocument(wireJson: String): InkWireDecodeResult<RenderDocument> {
        val problems = mutableListOf<InkProblem>()
        val json = parseRoot(wireJson, InkWireLimits.MAX_DOCUMENT_BYTES, "document", problems)
            ?: return InkWireDecodeResult(null, problems)
        json.rejectUnknown(setOf("v", "doc", "rev", "meta", "roots"), "$", problems)
        val version = json.requiredInt("v", "$", problems)
        val documentId = json.requiredString("doc", "$", problems)
        val revision = json.requiredInt("rev", "$", problems)
        val metadata = json.optionalObject("meta", "$", problems)?.let {
            decodeJsonObject(it, "$.meta", 1, problems)
        }.orEmpty()
        val rootsJson = json.requiredArray("roots", "$", problems)
        val roots = rootsJson?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val nodeJson = array.objectAt(index, "$.roots", problems)
                    nodeJson?.let { decodeNode(it, "$.roots[$index]", 1, problems) }?.let(::add)
                }
            }
        }.orEmpty()
        val value = if (version != null && documentId != null && revision != null && problems.isEmpty()) {
            RenderDocument(roots, metadata, documentId, revision, version)
        } else {
            null
        }
        return InkWireDecodeResult(value, problems)
    }

    fun decodePatch(wireJson: String): InkWireDecodeResult<RenderPatch> {
        val problems = mutableListOf<InkProblem>()
        val json = parseRoot(wireJson, InkWireLimits.MAX_PATCH_BYTES, "patch", problems)
            ?: return InkWireDecodeResult(null, problems)
        json.rejectUnknown(setOf("v", "doc", "baseRev", "targetRev", "changes"), "$", problems)
        val version = json.requiredInt("v", "$", problems)
        val documentId = json.requiredString("doc", "$", problems)
        val baseRevision = json.requiredInt("baseRev", "$", problems)
        val targetRevision = json.requiredInt("targetRev", "$", problems)
        val changesJson = json.requiredArray("changes", "$", problems)
        val changes = changesJson?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val changeJson = array.objectAt(index, "$.changes", problems)
                    changeJson?.let { decodeChange(it, "$.changes[$index]", problems) }?.let(::add)
                }
            }
        }.orEmpty()
        val value = if (
            version != null && documentId != null && baseRevision != null && targetRevision != null &&
            problems.isEmpty()
        ) {
            RenderPatch(changes, documentId, baseRevision, targetRevision, version)
        } else {
            null
        }
        return InkWireDecodeResult(value, problems)
    }

    fun decodeProblemReport(wireJson: String): InkWireDecodeResult<InkProblemReport> {
        val problems = mutableListOf<InkProblem>()
        val json = parseRoot(wireJson, InkWireLimits.MAX_PROBLEM_REPORT_BYTES, "problem report", problems)
            ?: return InkWireDecodeResult(null, problems)
        json.rejectUnknown(setOf("v", "problems"), "$", problems)
        val version = json.requiredInt("v", "$", problems)
        val problemArray = json.requiredArray("problems", "$", problems)
        val decodedProblems = problemArray?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.objectAt(index, "$.problems", problems)
                    item?.let { decodeProblem(it, "$.problems[$index]", problems) }?.let(::add)
                }
            }
        }.orEmpty()
        return InkWireDecodeResult(
            if (version != null && problems.isEmpty()) InkProblemReport(decodedProblems, version) else null,
            problems,
        )
    }

    private fun decodeNode(
        json: JSONObject,
        path: String,
        depth: Int,
        problems: MutableList<InkProblem>,
    ): RenderNode? {
        if (depth > InkWireLimits.MAX_DEPTH) {
            problems += wireProblem(
                InkProblemCodes.BUDGET_DEPTH,
                "Render tree exceeds the ${InkWireLimits.MAX_DEPTH} level depth budget",
                path,
            )
            return null
        }
        json.rejectUnknown(setOf("id", "t", "x", "a", "s", "e", "d", "c"), path, problems)
        val id = json.requiredString("id", path, problems)
        val type = json.requiredString("t", path, problems)
        val text = json.optionalString("x", path, problems)
        val attributes = json.optionalObject("a", path, problems)?.let {
            decodeJsonObject(it, "$path.a", 1, problems)
        }.orEmpty()
        val style = json.optionalObject("s", path, problems)?.let {
            decodeStringMap(it, "$path.s", problems)
        }.orEmpty()
        val events = json.optionalObject("e", path, problems)?.let {
            decodeEvents(it, "$path.e", problems)
        }.orEmpty()
        val dataset = json.optionalObject("d", path, problems)?.let {
            decodeJsonObject(it, "$path.d", 1, problems)
        }.orEmpty()
        val children = json.optionalArray("c", path, problems)?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val child = array.objectAt(index, "$path.c", problems)
                    child?.let { decodeNode(it, "$path.c[$index]", depth + 1, problems) }?.let(::add)
                }
            }
        }.orEmpty()
        return if (id != null && type != null) {
            RenderNode(id, type, text, attributes, style, events, dataset, children)
        } else {
            null
        }
    }

    private fun decodeEvents(
        json: JSONObject,
        path: String,
        problems: MutableList<InkProblem>,
    ): Map<String, InkActionBinding> = linkedMapOf<String, InkActionBinding>().also { result ->
        json.sortedKeys().forEach { event ->
            val value = json.raw(event)
            if (value !is JSONObject) {
                problems += wireType("$path.$event", "object", value)
                return@forEach
            }
            value.rejectUnknown(setOf("catch", "id"), "$path.$event", problems)
            val catches = value.requiredBoolean("catch", "$path.$event", problems)
            val actionId = value.requiredString("id", "$path.$event", problems)
            if (catches != null && actionId != null) result[event] = InkActionBinding(actionId, catches)
        }
    }

    private fun decodeChange(
        json: JSONObject,
        path: String,
        problems: MutableList<InkProblem>,
    ): RenderChange? {
        val operation = json.requiredString("op", path, problems) ?: return null
        val nodeId = json.requiredString("id", path, problems) ?: return null
        fun nullableParent(): String? = json.requiredNullableString("parent", path, problems)
        return when (operation) {
            "add" -> {
                json.rejectUnknown(setOf("op", "id", "parent", "index", "node"), path, problems)
                val parent = nullableParent()
                val index = json.requiredInt("index", path, problems)
                val node = json.requiredObject("node", path, problems)?.let {
                    decodeNode(it, "$path.node", 1, problems)
                }
                if (index != null && node != null) RenderChange.NodeAdded(nodeId, parent, index, node) else null
            }
            "remove" -> {
                json.rejectUnknown(setOf("op", "id", "parent", "index"), path, problems)
                val parent = nullableParent()
                val index = json.requiredInt("index", path, problems)
                index?.let { RenderChange.NodeRemoved(nodeId, parent, it) }
            }
            "move" -> {
                json.rejectUnknown(setOf("op", "id", "parent", "from", "to"), path, problems)
                val parent = nullableParent()
                val from = json.requiredInt("from", path, problems)
                val to = json.requiredInt("to", path, problems)
                if (from != null && to != null) RenderChange.NodeMoved(nodeId, parent, from, to) else null
            }
            "text" -> {
                json.rejectUnknown(setOf("op", "id", "value"), path, problems)
                json.requiredString("value", path, problems)?.let { RenderChange.TextChanged(nodeId, it) }
            }
            "attr" -> {
                json.rejectUnknown(setOf("op", "id", "name", "value"), path, problems)
                val name = json.requiredString("name", path, problems)
                val raw = json.requiredRaw("value", path, problems)
                if (name != null && raw !== Missing) {
                    RenderChange.AttributeChanged(
                        nodeId,
                        name,
                        decodeJsonValue(raw, "$path.value", 1, problems),
                    )
                } else {
                    null
                }
            }
            "style" -> {
                json.rejectUnknown(setOf("op", "id", "name", "value"), path, problems)
                val name = json.requiredString("name", path, problems)
                val value = json.requiredNullableString("value", path, problems)
                name?.let { RenderChange.StyleChanged(nodeId, it, value) }
            }
            "event" -> {
                json.rejectUnknown(setOf("op", "id", "name", "value"), path, problems)
                val name = json.requiredString("name", path, problems)
                val raw = json.requiredRaw("value", path, problems)
                val value = when (raw) {
                    Missing -> null
                    JSONObject.NULL -> null
                    is JSONObject -> {
                        raw.rejectUnknown(setOf("catch", "id"), "$path.value", problems)
                        val catches = raw.requiredBoolean("catch", "$path.value", problems)
                        val actionId = raw.requiredString("id", "$path.value", problems)
                        if (catches != null && actionId != null) InkActionBinding(actionId, catches) else null
                    }
                    else -> {
                        problems += wireType("$path.value", "object or null", raw)
                        null
                    }
                }
                if (name != null && raw !== Missing) RenderChange.EventChanged(nodeId, name, value) else null
            }
            "dataset" -> {
                json.rejectUnknown(setOf("op", "id", "name", "value"), path, problems)
                val name = json.requiredString("name", path, problems)
                val raw = json.requiredRaw("value", path, problems)
                if (name != null && raw !== Missing) {
                    RenderChange.DatasetChanged(
                        nodeId,
                        name,
                        decodeJsonValue(raw, "$path.value", 1, problems),
                    )
                } else {
                    null
                }
            }
            else -> {
                json.rejectUnknown(setOf("op", "id"), path, problems)
                problems += wireProblem(InkProblemCodes.WIRE_INVALID, "Unknown render change '$operation'", "$path.op")
                null
            }
        }
    }

    private fun decodeProblem(
        json: JSONObject,
        path: String,
        decoderProblems: MutableList<InkProblem>,
    ): InkProblem? {
        json.rejectUnknown(setOf("code", "message", "severity", "line", "col", "feature"), path, decoderProblems)
        val code = json.requiredString("code", path, decoderProblems)
        val message = json.requiredString("message", path, decoderProblems)
        val severityName = json.requiredString("severity", path, decoderProblems)
        val severity = when (severityName) {
            "error" -> InkProblemSeverity.ERROR
            "warning" -> InkProblemSeverity.WARNING
            null -> null
            else -> {
                decoderProblems += wireProblem(
                    InkProblemCodes.WIRE_INVALID,
                    "Unknown problem severity '$severityName'",
                    "$path.severity",
                )
                null
            }
        }
        val line = json.optionalInt("line", path, decoderProblems)
        val column = json.optionalInt("col", path, decoderProblems)
        val feature = json.optionalString("feature", path, decoderProblems)
        return if (code != null && message != null && severity != null) {
            InkProblem(code, message, severity, line, column, feature)
        } else {
            null
        }
    }

    private fun parseRoot(
        wireJson: String,
        maxBytes: Int,
        label: String,
        problems: MutableList<InkProblem>,
    ): JSONObject? {
        val bytes = wireJson.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > maxBytes) {
            problems += InkProblem(
                InkProblemCodes.BUDGET_SIZE,
                "Ink $label exceeds the $maxBytes byte wire budget",
                feature = label,
            )
            return null
        }
        return try {
            JSONObject(wireJson)
        } catch (error: Exception) {
            problems += InkProblem(
                InkProblemCodes.WIRE_INVALID,
                "Invalid Ink $label JSON: ${error.message ?: "parse failure"}",
                feature = "$",
            )
            null
        }
    }
}

object InkWireValidator {
    fun validateDocument(
        document: RenderDocument,
        wireByteSize: Int = document.toWireJson().toByteArray(StandardCharsets.UTF_8).size,
    ): List<InkProblem> {
        val problems = mutableListOf<InkProblem>()
        validateHeader(document.version, document.documentId, document.revision, problems)
        if (wireByteSize > InkWireLimits.MAX_DOCUMENT_BYTES) {
            problems += budgetSize("document", wireByteSize, InkWireLimits.MAX_DOCUMENT_BYTES)
        }
        val metadataBytes = document.metadata.jsonByteSize()
        if (metadataBytes > InkWireLimits.MAX_METADATA_BYTES) {
            problems += budgetSize("meta", metadataBytes, InkWireLimits.MAX_METADATA_BYTES)
        }
        if (jsonDepth(document.metadata) > InkWireLimits.MAX_JSON_VALUE_DEPTH) {
            problems += wireProblem(
                InkProblemCodes.BUDGET_DEPTH,
                "Document metadata exceeds the ${InkWireLimits.MAX_JSON_VALUE_DEPTH} level JSON depth budget",
                "$.meta",
            )
        }
        val ids = linkedSetOf<String>()
        var nodeCount = 0
        fun validateNode(node: RenderNode, depth: Int, path: String) {
            nodeCount++
            if (depth > InkWireLimits.MAX_DEPTH) {
                problems += wireProblem(
                    InkProblemCodes.BUDGET_DEPTH,
                    "Render tree exceeds the ${InkWireLimits.MAX_DEPTH} level depth budget",
                    path,
                )
                return
            }
            validateNode(node, path, ids, problems)
            node.children.forEachIndexed { index, child -> validateNode(child, depth + 1, "$path.c[$index]") }
        }
        document.roots.forEachIndexed { index, node -> validateNode(node, 1, "$.roots[$index]") }
        if (nodeCount > InkWireLimits.MAX_NODES) {
            problems += InkProblem(
                InkProblemCodes.BUDGET_NODES,
                "Render document has $nodeCount nodes; maximum is ${InkWireLimits.MAX_NODES}",
                feature = "roots",
            )
        }
        return problems.distinct()
    }

    fun validatePatch(
        patch: RenderPatch,
        wireByteSize: Int = patch.toWireJson().toByteArray(StandardCharsets.UTF_8).size,
    ): List<InkProblem> {
        val problems = mutableListOf<InkProblem>()
        validateHeader(patch.version, patch.documentId, patch.baseRevision, problems)
        if (patch.targetRevision != patch.baseRevision + 1 || patch.targetRevision < 1) {
            problems += wireProblem(
                InkProblemCodes.WIRE_REVISION,
                "Patch targetRev must be exactly baseRev + 1",
                "$.targetRev",
            )
        }
        if (wireByteSize > InkWireLimits.MAX_PATCH_BYTES) {
            problems += budgetSize("patch", wireByteSize, InkWireLimits.MAX_PATCH_BYTES)
        }
        if (patch.changes.size > InkWireLimits.MAX_CHANGES) {
            problems += InkProblem(
                InkProblemCodes.BUDGET_SIZE,
                "Patch has ${patch.changes.size} changes; maximum is ${InkWireLimits.MAX_CHANGES}",
                feature = "changes",
            )
        }
        patch.changes.forEachIndexed { index, change ->
            val path = "$.changes[$index]"
            validateNodeId(change.nodeId, "$path.id", problems)
            when (change) {
                is RenderChange.NodeAdded -> {
                    validateParent(change.parentId, "$path.parent", problems)
                    validateIndex(change.index, "$path.index", problems)
                    if (change.node.id != change.nodeId) {
                        problems += wireProblem(
                            InkProblemCodes.WIRE_ID,
                            "Added node id must match the change id",
                            "$path.node.id",
                        )
                    }
                    val ids = linkedSetOf<String>()
                    var count = 0
                    fun validateAdded(node: RenderNode, depth: Int, nodePath: String) {
                        count++
                        if (depth > InkWireLimits.MAX_DEPTH) {
                            problems += wireProblem(
                                InkProblemCodes.BUDGET_DEPTH,
                                "Added subtree exceeds the ${InkWireLimits.MAX_DEPTH} level depth budget",
                                nodePath,
                            )
                            return
                        }
                        validateNode(node, nodePath, ids, problems)
                        node.children.forEachIndexed { childIndex, child ->
                            validateAdded(child, depth + 1, "$nodePath.c[$childIndex]")
                        }
                    }
                    validateAdded(change.node, 1, "$path.node")
                    if (count > InkWireLimits.MAX_NODES) {
                        problems += InkProblem(
                            InkProblemCodes.BUDGET_NODES,
                            "Added subtree has $count nodes; maximum is ${InkWireLimits.MAX_NODES}",
                            feature = "$path.node",
                        )
                    }
                }
                is RenderChange.NodeRemoved -> {
                    validateParent(change.parentId, "$path.parent", problems)
                    validateIndex(change.index, "$path.index", problems)
                }
                is RenderChange.NodeMoved -> {
                    validateParent(change.parentId, "$path.parent", problems)
                    validateIndex(change.fromIndex, "$path.from", problems)
                    validateIndex(change.toIndex, "$path.to", problems)
                }
                is RenderChange.TextChanged -> validateText(change.value, "$path.value", problems)
                is RenderChange.AttributeChanged -> {
                    validateName(change.name, "$path.name", problems)
                    validateJsonValue(change.value, "$path.value", problems)
                }
                is RenderChange.StyleChanged -> {
                    validateStyle(change.name, change.value, "$path.value", problems)
                }
                is RenderChange.EventChanged -> {
                    validateName(change.name, "$path.name", problems)
                    change.value?.let { validateAction(it, "$path.value", problems) }
                }
                is RenderChange.DatasetChanged -> {
                    validateDatasetKey(change.name, "$path.name", problems)
                    validateJsonValue(change.value, "$path.value", problems)
                }
            }
        }
        return problems.distinct()
    }

    fun validateProblemReport(
        report: InkProblemReport,
        wireByteSize: Int = report.toWireJson().toByteArray(StandardCharsets.UTF_8).size,
    ): List<InkProblem> = buildList {
        if (report.version != InkWire.VERSION) {
            add(wireProblem(InkProblemCodes.WIRE_VERSION, "Unsupported Ink wire version ${report.version}", "$.v"))
        }
        if (wireByteSize > InkWireLimits.MAX_PROBLEM_REPORT_BYTES) {
            add(budgetSize("problem report", wireByteSize, InkWireLimits.MAX_PROBLEM_REPORT_BYTES))
        }
        report.problems.forEachIndexed { index, problem ->
            if (problem.code.isBlank() || problem.code.length > 128) {
                add(wireProblem(InkProblemCodes.WIRE_INVALID, "Problem code is out of bounds", "$.problems[$index].code"))
            }
            if (problem.message.isBlank() || problem.message.length > 1_024) {
                add(wireProblem(InkProblemCodes.WIRE_INVALID, "Problem message is out of bounds", "$.problems[$index].message"))
            }
            if (problem.line != null && problem.line < 1 || problem.column != null && problem.column < 1) {
                add(wireProblem(InkProblemCodes.WIRE_INVALID, "Problem location must be positive", "$.problems[$index]"))
            }
        }
    }

    private fun validateHeader(
        version: Int,
        documentId: String,
        revision: Int,
        problems: MutableList<InkProblem>,
    ) {
        if (version != InkWire.VERSION) {
            problems += wireProblem(InkProblemCodes.WIRE_VERSION, "Unsupported Ink wire version $version", "$.v")
        }
        if (documentId.isBlank() || documentId.length > InkWireLimits.MAX_DOCUMENT_ID_CHARS) {
            problems += wireProblem(
                InkProblemCodes.WIRE_ID,
                "Document id must contain 1..${InkWireLimits.MAX_DOCUMENT_ID_CHARS} characters",
                "$.doc",
            )
        }
        if (revision < 0) {
            problems += wireProblem(InkProblemCodes.WIRE_REVISION, "Revision must be non-negative", "$.rev")
        }
    }

    private fun validateNode(
        node: RenderNode,
        path: String,
        ids: MutableSet<String>,
        problems: MutableList<InkProblem>,
    ) {
        validateNodeId(node.id, "$path.id", problems)
        if (!ids.add(node.id)) {
            problems += wireProblem(InkProblemCodes.WIRE_ID, "Duplicate render node id '${node.id}'", "$path.id")
        }
        if (node.type !in NODE_TYPES) {
            problems += wireProblem(
                InkProblemCodes.COMPONENT_UNSUPPORTED,
                "Unsupported render node type '${node.type}'",
                "$path.t",
            )
        }
        node.text?.let { validateText(it, "$path.x", problems) }
        if (node.attributes.size > InkWireLimits.MAX_ATTRIBUTES) {
            problems += wireProblem(InkProblemCodes.BUDGET_SIZE, "Node attribute count exceeds ${InkWireLimits.MAX_ATTRIBUTES}", "$path.a")
        }
        val allowedAttributes = ATTRIBUTE_ALLOWLIST[node.type].orEmpty()
        node.attributes.forEach { (name, value) ->
            if (name !in allowedAttributes) {
                problems += wireProblem(
                    InkProblemCodes.ATTRIBUTE_UNSUPPORTED,
                    "Attribute '$name' is not allowed on '${node.type}'",
                    "$path.a.$name",
                )
            }
            validateName(name, "$path.a.$name", problems)
            validateJsonValue(value, "$path.a.$name", problems)
        }
        problems += InkComponentContract.validate(node, path)
        if (node.style.size > InkWireLimits.MAX_STYLES) {
            problems += wireProblem(InkProblemCodes.BUDGET_SIZE, "Node style count exceeds ${InkWireLimits.MAX_STYLES}", "$path.s")
        }
        node.style.forEach { (name, value) -> validateStyle(name, value, "$path.s.$name", problems) }
        if (node.events.size > InkWireLimits.MAX_EVENTS) {
            problems += wireProblem(InkProblemCodes.WIRE_ACTION, "Node event count exceeds ${InkWireLimits.MAX_EVENTS}", "$path.e")
        }
        node.events.forEach { (name, action) ->
            validateName(name, "$path.e.$name", problems)
            validateAction(action, "$path.e.$name", problems)
        }
        validateDataset(node.dataset, "$path.d", problems)
        if (node.type == "#text" && node.children.isNotEmpty()) {
            problems += wireProblem(InkProblemCodes.WIRE_INVALID, "Text leaf nodes cannot have children", "$path.c")
        }
    }

    private fun validateNodeId(id: String, path: String, problems: MutableList<InkProblem>) {
        if (id.isBlank() || id.length > InkWireLimits.MAX_NODE_ID_CHARS) {
            problems += wireProblem(
                InkProblemCodes.WIRE_ID,
                "Node id must contain 1..${InkWireLimits.MAX_NODE_ID_CHARS} characters",
                path,
            )
        }
    }

    private fun validateParent(parentId: String?, path: String, problems: MutableList<InkProblem>) {
        parentId?.let { validateNodeId(it, path, problems) }
    }

    private fun validateIndex(index: Int, path: String, problems: MutableList<InkProblem>) {
        if (index < 0) problems += wireProblem(InkProblemCodes.WIRE_INVALID, "Node index must be non-negative", path)
    }

    private fun validateText(value: String, path: String, problems: MutableList<InkProblem>) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > InkWireLimits.MAX_TEXT_BYTES) {
            problems += budgetSize(path, bytes, InkWireLimits.MAX_TEXT_BYTES)
        }
    }

    private fun validateName(name: String, path: String, problems: MutableList<InkProblem>) {
        if (name.isBlank() || name.length > InkWireLimits.MAX_DATASET_KEY_CHARS || !NAME.matches(name)) {
            problems += wireProblem(InkProblemCodes.WIRE_INVALID, "Wire name '$name' is out of bounds", path)
        }
    }

    private fun validateStyle(name: String, value: String?, path: String, problems: MutableList<InkProblem>) {
        if (name !in INK_V1_STYLE_PROPERTIES) {
            problems += wireProblem(InkProblemCodes.STYLE_UNSUPPORTED, "Style property '$name' is not in Ink Surface v1", path)
            return
        }
        value ?: return
        if (value.length > InkWireLimits.MAX_STYLE_VALUE_CHARS) {
            problems += wireProblem(InkProblemCodes.BUDGET_SIZE, "Style value is too long", path)
            return
        }
        val (parsed, parsedProblems) = WxssParser("").parseInline("$name: $value")
        if (parsed.keys != setOf(name) || parsedProblems.any { it.code != InkProblemCodes.COLOR_LITERAL }) {
            problems += wireProblem(InkProblemCodes.STYLE_UNSUPPORTED, "Invalid value '$value' for '$name'", path)
        }
    }

    private fun validateAction(
        action: InkActionBinding,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        if (action.actionId.isBlank() || action.actionId.length > InkWireLimits.MAX_ACTION_ID_CHARS) {
            problems += wireProblem(
                InkProblemCodes.WIRE_ACTION,
                "Action id must contain 1..${InkWireLimits.MAX_ACTION_ID_CHARS} characters",
                "$path.id",
            )
        }
    }

    private fun validateDataset(
        dataset: Map<String, Any?>,
        path: String,
        problems: MutableList<InkProblem>,
    ) {
        if (dataset.size > InkWireLimits.MAX_DATASET_ENTRIES) {
            problems += wireProblem(
                InkProblemCodes.WIRE_DATASET,
                "Dataset entry count exceeds ${InkWireLimits.MAX_DATASET_ENTRIES}",
                path,
            )
        }
        val bytes = dataset.jsonByteSize()
        if (bytes > InkWireLimits.MAX_DATASET_BYTES) {
            problems += budgetSize(path, bytes, InkWireLimits.MAX_DATASET_BYTES)
        }
        dataset.forEach { (name, value) ->
            validateDatasetKey(name, "$path.$name", problems)
            validateJsonValue(value, "$path.$name", problems)
        }
    }

    private fun validateDatasetKey(name: String, path: String, problems: MutableList<InkProblem>) {
        if (name.isBlank() || name.length > InkWireLimits.MAX_DATASET_KEY_CHARS || !DATASET_NAME.matches(name)) {
            problems += wireProblem(InkProblemCodes.WIRE_DATASET, "Dataset key '$name' is out of bounds", path)
        }
    }

    private fun validateJsonValue(value: Any?, path: String, problems: MutableList<InkProblem>) {
        if (jsonDepth(value) > InkWireLimits.MAX_JSON_VALUE_DEPTH) {
            problems += wireProblem(
                InkProblemCodes.BUDGET_DEPTH,
                "JSON value exceeds the ${InkWireLimits.MAX_JSON_VALUE_DEPTH} level depth budget",
                path,
            )
        }
        if (!jsonTypesValid(value)) {
            problems += wireProblem(InkProblemCodes.WIRE_TYPE, "Value is not a JSON value", path)
        }
    }

    private val NODE_TYPES = InkComponentContract.supportedComponents + "#text"
    private val ATTRIBUTE_ALLOWLIST = InkComponentContract.supportedComponents.associateWith(
        InkComponentContract::attributesFor,
    ) + ("#text" to emptySet())
    private val NAME = Regex("[A-Za-z][A-Za-z0-9:_-]{0,63}")
    private val DATASET_NAME = Regex("[A-Za-z0-9_:-]{1,64}")
}

private object Missing

private fun JSONObject.sortedKeys(): List<String> = keys().asSequence().toList().sorted()

private fun JSONObject.raw(name: String): Any = get(name)

private fun JSONObject.rejectUnknown(
    allowed: Set<String>,
    path: String,
    problems: MutableList<InkProblem>,
) {
    sortedKeys().filterNot(allowed::contains).forEach { name ->
        problems += wireProblem(
            InkProblemCodes.WIRE_UNKNOWN_FIELD,
            "Unknown wire field '$name'",
            "$path.$name",
        )
    }
}

private fun JSONObject.requiredRaw(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): Any {
    if (!has(name)) {
        problems += wireProblem(InkProblemCodes.WIRE_INVALID, "Missing required field '$name'", "$path.$name")
        return Missing
    }
    return raw(name)
}

private fun JSONObject.requiredString(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): String? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing) return null
    if (value is String) return value
    problems += wireType("$path.$name", "string", value)
    return null
}

private fun JSONObject.optionalString(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): String? {
    if (!has(name)) return null
    val value = raw(name)
    if (value is String) return value
    problems += wireType("$path.$name", "string", value)
    return null
}

private fun JSONObject.requiredNullableString(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): String? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing || value === JSONObject.NULL) return null
    if (value is String) return value
    problems += wireType("$path.$name", "string or null", value)
    return null
}

private fun JSONObject.requiredBoolean(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): Boolean? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing) return null
    if (value is Boolean) return value
    problems += wireType("$path.$name", "boolean", value)
    return null
}

private fun JSONObject.requiredInt(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): Int? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing) return null
    return value.strictIntOrNull() ?: run {
        problems += wireType("$path.$name", "integer", value)
        null
    }
}

private fun JSONObject.optionalInt(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): Int? {
    if (!has(name)) return null
    val value = raw(name)
    return value.strictIntOrNull() ?: run {
        problems += wireType("$path.$name", "integer", value)
        null
    }
}

private fun Any.strictIntOrNull(): Int? {
    val number = this as? Number ?: return null
    val double = number.toDouble()
    if (!double.isFinite() || double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) return null
    return double.toInt()
}

private fun JSONObject.requiredObject(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): JSONObject? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing) return null
    if (value is JSONObject) return value
    problems += wireType("$path.$name", "object", value)
    return null
}

private fun JSONObject.optionalObject(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): JSONObject? {
    if (!has(name)) return null
    val value = raw(name)
    if (value is JSONObject) return value
    problems += wireType("$path.$name", "object", value)
    return null
}

private fun JSONObject.requiredArray(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): JSONArray? {
    val value = requiredRaw(name, path, problems)
    if (value === Missing) return null
    if (value is JSONArray) return value
    problems += wireType("$path.$name", "array", value)
    return null
}

private fun JSONObject.optionalArray(
    name: String,
    path: String,
    problems: MutableList<InkProblem>,
): JSONArray? {
    if (!has(name)) return null
    val value = raw(name)
    if (value is JSONArray) return value
    problems += wireType("$path.$name", "array", value)
    return null
}

private fun JSONArray.objectAt(
    index: Int,
    path: String,
    problems: MutableList<InkProblem>,
): JSONObject? {
    val value = get(index)
    if (value is JSONObject) return value
    problems += wireType("$path[$index]", "object", value)
    return null
}

private fun decodeStringMap(
    json: JSONObject,
    path: String,
    problems: MutableList<InkProblem>,
): Map<String, String> = linkedMapOf<String, String>().also { result ->
    json.sortedKeys().forEach { name ->
        val value = json.raw(name)
        if (value is String) result[name] = value else problems += wireType("$path.$name", "string", value)
    }
}

private fun decodeJsonObject(
    json: JSONObject,
    path: String,
    depth: Int,
    problems: MutableList<InkProblem>,
): Map<String, Any?> = linkedMapOf<String, Any?>().also { result ->
    json.sortedKeys().forEach { name -> result[name] = decodeJsonValue(json.raw(name), "$path.$name", depth, problems) }
}

private fun decodeJsonValue(
    value: Any,
    path: String,
    depth: Int,
    problems: MutableList<InkProblem>,
): Any? {
    if (depth > InkWireLimits.MAX_JSON_VALUE_DEPTH) {
        problems += wireProblem(
            InkProblemCodes.BUDGET_DEPTH,
            "JSON value exceeds the ${InkWireLimits.MAX_JSON_VALUE_DEPTH} level depth budget",
            path,
        )
        return null
    }
    return when (value) {
        JSONObject.NULL -> null
        is String, is Boolean -> value
        is Number -> if (value.toDouble().isFinite()) value else {
            problems += wireType(path, "finite number", value)
            null
        }
        is JSONObject -> decodeJsonObject(value, path, depth + 1, problems)
        is JSONArray -> MutableList(value.length()) { index ->
            decodeJsonValue(value.get(index), "$path[$index]", depth + 1, problems)
        }
        else -> {
            problems += wireType(path, "JSON value", value)
            null
        }
    }
}

private fun jsonDepth(value: Any?): Int = when (value) {
    is Map<*, *> -> 1 + (value.values.maxOfOrNull(::jsonDepth) ?: 0)
    is List<*> -> 1 + (value.maxOfOrNull(::jsonDepth) ?: 0)
    else -> 0
}

private fun jsonTypesValid(value: Any?): Boolean = when (value) {
    null, is String, is Boolean -> true
    is Number -> value.toDouble().isFinite()
    is Map<*, *> -> value.keys.all { it is String } && value.values.all(::jsonTypesValid)
    is List<*> -> value.all(::jsonTypesValid)
    else -> false
}

private fun wireType(path: String, expected: String, actual: Any?): InkProblem = wireProblem(
    InkProblemCodes.WIRE_TYPE,
    "Expected $expected at $path, found ${actual.wireTypeName()}",
    path,
)

private fun Any?.wireTypeName(): String = when (this) {
    null, JSONObject.NULL -> "null"
    is JSONObject, is Map<*, *> -> "object"
    is JSONArray, is List<*> -> "array"
    is String -> "string"
    is Boolean -> "boolean"
    is Number -> "number"
    else -> javaClass.simpleName
}

private fun wireProblem(code: String, message: String, path: String): InkProblem =
    InkProblem(code, message, feature = path)

private fun budgetSize(label: String, actual: Int, maximum: Int): InkProblem = InkProblem(
    InkProblemCodes.BUDGET_SIZE,
    "Ink $label is $actual bytes; maximum is $maximum bytes",
    feature = label,
)
