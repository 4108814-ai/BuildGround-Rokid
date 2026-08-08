package com.anezium.rokidbus.glasses

import android.os.Handler
import android.view.KeyEvent
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.ink.InkWireValidator
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderPatch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class InkResyncRequest(
    val currentDocumentId: String,
    val currentRevision: Int,
    val patchDocumentId: String,
    val patchBaseRevision: Int,
)

/** Controller-owned Ink state; attached overlay/activity views are disposable projections. */
internal class InkRendererLayer(
    private val main: Handler,
    private val onResyncNeeded: (InkResyncRequest) -> Unit,
    private val onAction: (String, Map<String, Any?>) -> Unit,
    private val onRendererError: (String, List<InkProblem>) -> Unit,
) {
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusInkDecode").apply { isDaemon = true }
    }
    private val decodeJobs = mutableListOf<DecodeJob>()
    private var generation = 0L
    private var store: InkNodeStore? = null
    private var attachedView: InkHudView? = null

    fun submit(
        surface: NexusSurface,
        onCommitted: () -> Unit,
    ) {
        val payload = surface.ink ?: return
        val documentJson = payload.documentJson
        val patchJson = payload.patchJson
        if ((documentJson == null) == (patchJson == null)) {
            log("Ink payload rejected id=${surface.surfaceId}: expected exactly one document or patch")
            onRendererError(
                surface.surfaceId,
                listOf(
                    InkProblem(
                        InkProblemCodes.WIRE_INVALID,
                        "Expected exactly one Ink document or patch",
                    ),
                ),
            )
            return
        }
        // A new full document replaces all queued work. Patches retain the
        // generation so a show immediately followed by patches commits in
        // document/revision order on the main Handler.
        val requestGeneration = if (documentJson != null) nextGeneration() else generation
        val job = DecodeJob()
        decodeJobs += job
        job.future = decoder.submit {
            val decoded = if (documentJson != null) {
                decodeDocument(documentJson)
            } else {
                decodePatch(requireNotNull(patchJson))
            }
            if (job.cancelled || Thread.currentThread().isInterrupted) return@submit
            main.post {
                decodeJobs.remove(job)
                if (job.cancelled || requestGeneration != generation) return@post
                when (decoded) {
                    is Decoded.Document -> {
                        val current = store
                        if (current?.documentId == decoded.value.documentId && decoded.value.revision <= current.revision) {
                            log(
                                "Ink stale document drop doc=${decoded.value.documentId} " +
                                    "rev=${decoded.value.revision} current=${current.revision}",
                            )
                            return@post
                        }
                        val next = InkNodeStore.from(decoded.value)
                        store = next
                        attachedView?.show(next, payload.debugActions)
                        onCommitted()
                    }
                    is Decoded.Patch -> {
                        val current = store
                        if (current == null) {
                            onResyncNeeded(
                                InkResyncRequest("", -1, decoded.value.documentId, decoded.value.baseRevision),
                            )
                            return@post
                        }
                        when (val result = InkNodeStore.Executor.apply(current, decoded.value)) {
                            is InkPatchApplyResult.Applied -> {
                                store = result.store
                                attachedView?.applyPatch(result.store, decoded.value.changes, payload.debugActions)
                                onCommitted()
                            }
                            is InkPatchApplyResult.Invalid -> {
                                logProblem("Ink patch rejected", result.problem)
                                onRendererError(surface.surfaceId, listOf(result.problem))
                            }
                            is InkPatchApplyResult.ResyncNeeded -> onResyncNeeded(
                                InkResyncRequest(
                                    result.currentDocumentId,
                                    result.currentRevision,
                                    result.patchDocumentId,
                                    result.patchBaseRevision,
                                ),
                            )
                        }
                    }
                    is Decoded.Invalid -> {
                        decoded.problems.forEach { logProblem("Ink wire rejected", it) }
                        onRendererError(surface.surfaceId, decoded.problems)
                    }
                }
            }
        }
    }

    fun attach(view: InkHudView, debugActions: Boolean) {
        if (attachedView !== view) {
            attachedView?.clearProjection()
            attachedView?.onAction = null
            attachedView = view
        }
        view.onAction = onAction
        store?.let { view.show(it, debugActions) }
    }

    fun detach(view: InkHudView) {
        if (attachedView !== view) return
        cancelPendingDecodes()
        view.onAction = null
        view.clearProjection()
        attachedView = null
    }

    fun handleKeyEvent(event: KeyEvent): Boolean = attachedView?.handleInkKeyEvent(event) == true

    fun clear() {
        nextGeneration()
        store = null
        attachedView?.clearProjection()
        attachedView?.onAction = null
    }

    fun destroy() {
        clear()
        attachedView = null
        decoder.shutdownNow()
    }

    fun identity(): Pair<String, Int>? = store?.let { it.documentId to it.revision }

    private fun nextGeneration(): Long {
        generation += 1
        cancelDecodeJobs()
        return generation
    }

    private fun cancelPendingDecodes() {
        generation += 1
        cancelDecodeJobs()
    }

    private fun cancelDecodeJobs() {
        decodeJobs.forEach { job ->
            job.cancelled = true
            job.future?.cancel(true)
        }
        decodeJobs.clear()
    }

    private fun decodeDocument(raw: String): Decoded {
        val decoded = RenderDocument.fromWireJson(raw)
        val value = decoded.value ?: return Decoded.Invalid(decoded.problems)
        val validation = InkWireValidator.validateDocument(
            value,
            raw.toByteArray(StandardCharsets.UTF_8).size,
        )
        return if (validation.isEmpty()) Decoded.Document(value) else Decoded.Invalid(validation)
    }

    private fun decodePatch(raw: String): Decoded {
        val decoded = RenderPatch.fromWireJson(raw)
        val value = decoded.value ?: return Decoded.Invalid(decoded.problems)
        val validation = InkWireValidator.validatePatch(
            value,
            raw.toByteArray(StandardCharsets.UTF_8).size,
        )
        return if (validation.isEmpty()) Decoded.Patch(value) else Decoded.Invalid(validation)
    }

    private fun logProblem(prefix: String, problem: InkProblem) {
        log("$prefix code=${problem.code} feature=${problem.feature.orEmpty()} message=${problem.message}")
    }

    private sealed interface Decoded {
        data class Document(val value: RenderDocument) : Decoded
        data class Patch(val value: RenderPatch) : Decoded
        data class Invalid(val problems: List<InkProblem>) : Decoded
    }

    private class DecodeJob {
        @Volatile var cancelled = false
        var future: Future<*>? = null
    }
}
