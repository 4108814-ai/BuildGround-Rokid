package com.anezium.rokidbus.ink

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class InkCompileResult(
    val document: RenderDocument?,
    val session: InkSession?,
    val problems: List<InkProblem>,
    val evaluatedExpressionCount: Int = 0,
) {
    val hasErrors: Boolean
        get() = problems.any { it.severity == InkProblemSeverity.ERROR }

    fun problemsToWireJson(): String = InkProblemReport(problems).toWireJson()
}

data class InkPatchResult(
    val patch: RenderPatch?,
    val document: RenderDocument?,
    val problems: List<InkProblem>,
    val evaluatedExpressionCount: Int = 0,
) {
    val hasErrors: Boolean
        get() = problems.any { it.severity == InkProblemSeverity.ERROR }

    fun problemsToWireJson(): String = InkProblemReport(problems).toWireJson()
}

data class InkProblemReport(
    val problems: List<InkProblem>,
    val version: Int = InkWire.VERSION,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("v", version)
        put("problems", problems.toJsonArray())
    }

    fun toWireJson(): String = DeterministicJson.stringify(toJsonObject())

    companion object {
        fun fromWireJson(json: String): InkWireDecodeResult<InkProblemReport> =
            InkWireCodec.decodeProblemReport(json)
    }
}

object InkEngine {
    fun compile(source: InkSource, hostData: JSONObject? = null): InkCompileResult {
        val blocks = SfcSplitter.split(source)
        val problems = blocks.problems.toMutableList()
        val page = blocks.page
        if (source.authoredByteSize() > MAX_PAGE_BYTES) {
            problems += InkProblem(
                InkProblemCodes.BUDGET_SIZE,
                "Input page exceeds the ${MAX_PAGE_BYTES / 1024} KiB page budget",
                feature = "page",
            )
        }

        val host = hostData?.toInkObject() ?: linkedMapOf()
        val data = deepMerge(blocks.definition, host)
        if (data.jsonByteSize() > MAX_DATA_BYTES) {
            problems += InkProblem(
                InkProblemCodes.BUDGET_SIZE,
                "Merged data exceeds the ${MAX_DATA_BYTES / 1024} KiB data budget",
                feature = "data",
            )
        }

        val markup = if (page != null) {
            WxmlParser(page, blocks.pageLocation).parse()
        } else {
            MarkupParseResult(emptyList(), emptyList())
        }
        problems += markup.problems
        val styles = WxssParser(blocks.style, blocks.styleLocation).parse()
        problems += styles.problems

        if (problems.any { it.severity == InkProblemSeverity.ERROR }) {
            return InkCompileResult(null, null, problems.toList())
        }

        val cache = EvaluationCache()
        val documentId = UUID.randomUUID().toString()
        val binding = BindingRenderer(
            roots = markup.roots,
            rules = styles.rules,
            metadata = blocks.metadata,
            data = data,
            cache = cache,
            documentId = documentId,
            revision = 0,
        ).render()
        problems += binding.problems
        val document = binding.document
        if (document == null || problems.any { it.severity == InkProblemSeverity.ERROR }) {
            return InkCompileResult(null, null, problems.toList(), binding.evaluatedExpressionCount)
        }
        val session = InkSession(markup.roots, styles.rules, blocks.metadata, data, cache, document)
        return InkCompileResult(document, session, problems.toList(), binding.evaluatedExpressionCount)
    }

    internal const val MAX_PAGE_BYTES = 32 * 1024
    internal const val MAX_DATA_BYTES = 16 * 1024
}

/**
 * Mutable compiled state for one caller thread. Create, patch, and discard a session on the same thread.
 * Published documents and patches are immutable snapshots and may be handed to other threads.
 */
class InkSession internal constructor(
    private val templateRoots: List<TemplateNode>,
    private val styleRules: List<InkStyleRule>,
    private val metadata: InkObject,
    initialData: InkObject,
    initialCache: EvaluationCache,
    initialDocument: RenderDocument,
) {
    private val ownerThread = Thread.currentThread()
    private var data: InkObject = initialData.deepCopyObject()
    private var cache: EvaluationCache = initialCache
    private var revision: Int = initialDocument.revision

    var document: RenderDocument = initialDocument
        private set

    fun applyPatch(patchData: JSONObject): InkPatchResult {
        if (Thread.currentThread() !== ownerThread) {
            return InkPatchResult(
                null,
                document,
                listOf(
                    InkProblem(
                        InkProblemCodes.THREAD_INVALID,
                        "InkSession.applyPatch must run on the thread that created the session",
                        feature = "InkSession",
                    ),
                ),
            )
        }

        val trialData = data.deepCopyObject()
        val update = applySetDataPatch(trialData, patchData)
        if (update.problems.isNotEmpty()) return InkPatchResult(null, document, update.problems)
        if (trialData.jsonByteSize() > InkEngine.MAX_DATA_BYTES) {
            return InkPatchResult(
                null,
                document,
                listOf(
                    InkProblem(
                        InkProblemCodes.BUDGET_SIZE,
                        "Patched data exceeds the ${InkEngine.MAX_DATA_BYTES / 1024} KiB data budget",
                        feature = "data",
                    ),
                ),
            )
        }

        if (revision == Int.MAX_VALUE) {
            return InkPatchResult(
                null,
                document,
                listOf(
                    InkProblem(
                        InkProblemCodes.WIRE_REVISION,
                        "Ink document revision is exhausted",
                        feature = document.documentId,
                    ),
                ),
            )
        }
        val targetRevision = revision + 1
        val trialCache = cache.copyForUpdate()
        val binding = BindingRenderer(
            templateRoots,
            styleRules,
            metadata,
            trialData,
            trialCache,
            document.documentId,
            targetRevision,
            update.dirtyPaths,
        ).render()
        val next = binding.document
        if (next == null || binding.problems.any { it.severity == InkProblemSeverity.ERROR }) {
            return InkPatchResult(null, document, binding.problems, binding.evaluatedExpressionCount)
        }
        val patch = RenderDiffer.diff(document, next)
        data = trialData
        cache = trialCache
        revision = targetRevision
        document = next
        return InkPatchResult(patch, next, binding.problems, binding.evaluatedExpressionCount)
    }
}

private fun InkSource.authoredByteSize(): Int = when (this) {
    is InkSource.Sfc -> text.toByteArray(StandardCharsets.UTF_8).size
    is InkSource.MultiFile -> {
        val definitionBytes = definition?.toString()?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
        wxml.toByteArray(StandardCharsets.UTF_8).size + wxss.toByteArray(StandardCharsets.UTF_8).size + definitionBytes
    }
}

private data class DataUpdateResult(
    val dirtyPaths: List<DataPath>,
    val problems: List<InkProblem>,
)

private fun applySetDataPatch(target: InkObject, patch: JSONObject): DataUpdateResult {
    val dirty = mutableListOf<DataPath>()
    val problems = mutableListOf<InkProblem>()
    patch.keys().asSequence().toList().sorted().forEach { key ->
        val path = parseDataPath(key)
        if (path == null) {
            problems += InkProblem(
                InkProblemCodes.MARKUP_INVALID,
                "Invalid setData path '$key'",
                feature = key,
            )
            return@forEach
        }
        val value = patch.get(key).let { jsonValue ->
            when (jsonValue) {
                is JSONObject -> jsonValue.toInkObject()
                org.json.JSONObject.NULL -> null
                is org.json.JSONArray -> JSONObject().put("value", jsonValue).toInkObject()["value"]
                else -> jsonValue
            }
        }
        setPathValue(target, path.segments, value = value.deepCopyInk())
        dirty += path
    }
    return DataUpdateResult(dirty, problems)
}

private fun parseDataPath(source: String): DataPath? {
    if (source.isEmpty()) return null
    val segments = mutableListOf<Any>()
    var index = 0
    val rootStart = index
    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$' || source[index] == '-')) index++
    if (index == rootStart) return null
    segments += source.substring(rootStart, index)
    while (index < source.length) {
        when (source[index]) {
            '.' -> {
                index++
                val start = index
                while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$' || source[index] == '-')) index++
                if (index == start) return null
                segments += source.substring(start, index)
            }
            '[' -> {
                index++
                val start = index
                while (index < source.length && source[index].isDigit()) index++
                if (index == start || source.getOrNull(index) != ']') return null
                val arrayIndex = source.substring(start, index).toIntOrNull() ?: return null
                segments += arrayIndex
                index++
            }
            else -> return null
        }
    }
    return DataPath(segments)
}

private fun setPathValue(container: Any, segments: List<Any>, position: Int = 0, value: Any?) {
    val segment = segments[position]
    val last = position == segments.lastIndex
    when {
        container is MutableMap<*, *> && segment is String -> {
            @Suppress("UNCHECKED_CAST")
            val map = container as MutableMap<String, Any?>
            if (last) {
                map[segment] = value
            } else {
                val next = segments[position + 1]
                var child = map[segment]
                if (next is Int && child !is MutableList<*> || next is String && child !is MutableMap<*, *>) {
                    child = if (next is Int) mutableListOf<Any?>() else linkedMapOf<String, Any?>()
                    map[segment] = child
                }
                setPathValue(child!!, segments, position + 1, value)
            }
        }
        container is MutableList<*> && segment is Int -> {
            @Suppress("UNCHECKED_CAST")
            val list = container as MutableList<Any?>
            while (list.size <= segment) list += null
            if (last) {
                list[segment] = value
            } else {
                val next = segments[position + 1]
                var child = list[segment]
                if (next is Int && child !is MutableList<*> || next is String && child !is MutableMap<*, *>) {
                    child = if (next is Int) mutableListOf<Any?>() else linkedMapOf<String, Any?>()
                    list[segment] = child
                }
                setPathValue(child!!, segments, position + 1, value)
            }
        }
    }
}
