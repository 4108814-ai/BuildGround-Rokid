package com.anezium.rokidbus.phone

import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkProblem
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.ink.InkSession
import com.anezium.rokidbus.ink.InkSource
import com.anezium.rokidbus.ink.InkWireValidator
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

internal data class PhoneInkSurfaceOwner(
    val pluginId: String,
    val localSurfaceId: String,
    val wireSurfaceId: String,
)

internal sealed interface PhoneInkCommandResult {
    data class Outgoing(
        val owner: PhoneInkSurfaceOwner,
        val path: String,
        val payload: JSONObject,
        val replaced: List<PhoneInkSurfaceOwner> = emptyList(),
    ) : PhoneInkCommandResult

    data class Noop(val owner: PhoneInkSurfaceOwner) : PhoneInkCommandResult

    data class Error(
        val owner: PhoneInkSurfaceOwner,
        val problems: List<InkProblem>,
    ) : PhoneInkCommandResult
}

internal sealed interface PhoneInkRemoteEventResult {
    data class Forward(val owner: PhoneInkSurfaceOwner) : PhoneInkRemoteEventResult
    data class Closed(val owner: PhoneInkSurfaceOwner) : PhoneInkRemoteEventResult
    data class Resync(val outgoing: PhoneInkCommandResult.Outgoing) : PhoneInkRemoteEventResult
    data object Ignore : PhoneInkRemoteEventResult
}

/** Owns every mutable [InkSession] on one dedicated thread. */
internal class PhoneInkSurfaceCoordinator(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val postResult: (() -> Unit) -> Unit = { action -> action() },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusInkCompile").apply { isDaemon = true }
    },
) : AutoCloseable {
    private data class Entry(
        val owner: PhoneInkSurfaceOwner,
        val session: InkSession,
        val handlesBack: Boolean,
        var closing: Boolean = false,
        var lastResyncAtMs: Long = Long.MIN_VALUE,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val schedulingLock = Any()
    private val ownerGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val globalGeneration = AtomicLong()

    fun show(
        owner: PhoneInkSurfaceOwner,
        page: String,
        data: JSONObject?,
        handlesBack: Boolean,
        callback: (PhoneInkCommandResult) -> Unit,
    ) = submit(owner, callback) {
        val compiled = InkEngine.compile(
            InkSource.Sfc(page),
            data?.let { JSONObject(it.toString()) },
        )
        val document = compiled.document
        val session = compiled.session
        if (compiled.hasErrors || document == null || session == null) {
            return@submit PhoneInkCommandResult.Error(owner, compiled.problems)
        }
        val wire = document.toWireJson()
        val problems = InkWireValidator.validateDocument(document, wire.toByteArray(Charsets.UTF_8).size)
        if (problems.isNotEmpty()) return@submit PhoneInkCommandResult.Error(owner, problems)

        val replaced = entries.values
            .filter { it.owner.wireSurfaceId != owner.wireSurfaceId }
            .map(Entry::owner)
        entries.clear()
        entries[owner.wireSurfaceId] = Entry(owner, session, handlesBack)
        PhoneInkCommandResult.Outgoing(
            owner = owner,
            path = BusPaths.SURFACE_SHOW,
            payload = ownerPayload(owner)
                .put("kind", InkSurfaceContract.KIND)
                .put("contentKey", document.documentId)
                .put("handlesBack", handlesBack)
                .put("ink", JSONObject().put("document", wire)),
            replaced = replaced,
        )
    }

    fun update(
        owner: PhoneInkSurfaceOwner,
        data: JSONObject,
        callback: (PhoneInkCommandResult) -> Unit,
    ) = submit(owner, callback) {
        val entry = entries[owner.wireSurfaceId]
            ?.takeIf { it.owner.pluginId == owner.pluginId && !it.closing }
            ?: return@submit sessionMissing(owner)
        val patched = entry.session.applyPatch(JSONObject(data.toString()))
        val patch = patched.patch
        if (patched.hasErrors || patch == null) {
            return@submit PhoneInkCommandResult.Error(owner, patched.problems)
        }
        val wire = patch.toWireJson()
        val problems = InkWireValidator.validatePatch(patch, wire.toByteArray(Charsets.UTF_8).size)
        if (problems.isNotEmpty()) return@submit PhoneInkCommandResult.Error(owner, problems)
        PhoneInkCommandResult.Outgoing(
            owner = owner,
            path = BusPaths.SURFACE_UPDATE,
            payload = ownerPayload(owner)
                .put("kind", InkSurfaceContract.KIND)
                .put("contentKey", patch.documentId)
                .put("handlesBack", entry.handlesBack)
                .put("ink", JSONObject().put("patch", wire)),
        )
    }

    fun hide(
        owner: PhoneInkSurfaceOwner,
        callback: (PhoneInkCommandResult) -> Unit,
    ) = submit(owner, callback) {
        val entry = entries[owner.wireSurfaceId]
            ?.takeIf { it.owner.pluginId == owner.pluginId }
            ?: return@submit PhoneInkCommandResult.Noop(owner)
        if (entry.closing) return@submit PhoneInkCommandResult.Noop(owner)
        entry.closing = true
        PhoneInkCommandResult.Outgoing(
            owner = owner,
            path = BusPaths.SURFACE_HIDE,
            payload = ownerPayload(owner),
        )
    }

    fun onRemoteEvent(
        wireSurfaceId: String,
        type: String,
        callback: (PhoneInkRemoteEventResult) -> Unit,
    ) {
        try {
            synchronized(schedulingLock) {
                val pluginId = wireSurfaceId.substringBefore(':')
                val ownerGeneration = ownerGenerations[pluginId]?.get()
                val allGeneration = globalGeneration.get()
                worker.execute {
                    val entry = entries[wireSurfaceId]
                    val result = when {
                        entry == null -> PhoneInkRemoteEventResult.Ignore
                        type == InkSurfaceContract.EVENT_CLOSED -> {
                            entries.remove(wireSurfaceId)
                            PhoneInkRemoteEventResult.Closed(entry.owner)
                        }
                        type == InkSurfaceContract.EVENT_RESYNC -> {
                            val now = nowMs()
                            if (entry.lastResyncAtMs != Long.MIN_VALUE &&
                                now - entry.lastResyncAtMs < RESYNC_MIN_INTERVAL_MS
                            ) {
                                PhoneInkRemoteEventResult.Ignore
                            } else {
                                entry.lastResyncAtMs = now
                                val document = entry.session.document
                                val wire = document.toWireJson()
                                PhoneInkRemoteEventResult.Resync(
                                    PhoneInkCommandResult.Outgoing(
                                        owner = entry.owner,
                                        path = BusPaths.SURFACE_UPDATE,
                                        payload = ownerPayload(entry.owner)
                                            .put("kind", InkSurfaceContract.KIND)
                                            .put("contentKey", document.documentId)
                                            .put("handlesBack", entry.handlesBack)
                                            .put("ink", JSONObject().put("document", wire)),
                                    ),
                                )
                            }
                        }
                        else -> PhoneInkRemoteEventResult.Forward(entry.owner)
                    }
                    postResult {
                        val ownerStillCurrent = entry == null ||
                            (entry.owner.pluginId == pluginId &&
                                ownerGenerations[pluginId]?.get() == ownerGeneration)
                        val stillCurrent = ownerStillCurrent && globalGeneration.get() == allGeneration
                        callback(
                            if (result is PhoneInkRemoteEventResult.Closed || stillCurrent) {
                                result
                            } else {
                                PhoneInkRemoteEventResult.Ignore
                            },
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            postResult { callback(PhoneInkRemoteEventResult.Ignore) }
        }
    }

    fun clearOwner(pluginId: String, callback: (List<PhoneInkSurfaceOwner>) -> Unit = {}) {
        try {
            synchronized(schedulingLock) {
                ownerGenerations.computeIfAbsent(pluginId) { AtomicLong() }.incrementAndGet()
                worker.execute {
                    val removed = entries.values.filter { it.owner.pluginId == pluginId }.map(Entry::owner)
                    removed.forEach { entries.remove(it.wireSurfaceId) }
                    postResult { callback(removed) }
                }
            }
        } catch (_: RejectedExecutionException) {
            postResult { callback(emptyList()) }
        }
    }

    fun clearForLinkLoss(callback: (List<PhoneInkSurfaceOwner>) -> Unit) {
        try {
            synchronized(schedulingLock) {
                globalGeneration.incrementAndGet()
                worker.execute {
                    val removed = entries.values.map(Entry::owner)
                    entries.clear()
                    postResult { callback(removed) }
                }
            }
        } catch (_: RejectedExecutionException) {
            postResult { callback(emptyList()) }
        }
    }

    override fun close() {
        synchronized(schedulingLock) {
            globalGeneration.incrementAndGet()
            worker.shutdownNow()
        }
    }

    private fun submit(
        owner: PhoneInkSurfaceOwner,
        callback: (PhoneInkCommandResult) -> Unit,
        command: () -> PhoneInkCommandResult,
    ) {
        try {
            synchronized(schedulingLock) {
                val ownerGeneration = ownerGenerations
                    .computeIfAbsent(owner.pluginId) { AtomicLong() }
                    .get()
                val allGeneration = globalGeneration.get()
                fun isCurrent(): Boolean = ownerGenerations[owner.pluginId]?.get() == ownerGeneration &&
                    globalGeneration.get() == allGeneration
                worker.execute {
                    val result = if (!isCurrent()) {
                        PhoneInkCommandResult.Noop(owner)
                    } else {
                        runCatching(command).getOrElse { failure ->
                            PhoneInkCommandResult.Error(
                                owner,
                                listOf(
                                    InkProblem(
                                        InkProblemCodes.WIRE_INVALID,
                                        "Ink command failed: ${failure.javaClass.simpleName}",
                                        feature = "phone_hub",
                                    ),
                                ),
                            )
                        }
                    }
                    postResult {
                        callback(if (isCurrent()) result else PhoneInkCommandResult.Noop(owner))
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            postResult {
                callback(
                    PhoneInkCommandResult.Error(
                        owner,
                        listOf(
                            InkProblem(
                                InkProblemCodes.WIRE_INVALID,
                                "Ink compiler is unavailable",
                                feature = "phone_hub",
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun sessionMissing(owner: PhoneInkSurfaceOwner): PhoneInkCommandResult.Error =
        PhoneInkCommandResult.Error(
            owner,
            listOf(
                InkProblem(
                    InkProblemCodes.SESSION_NOT_FOUND,
                    "No active Ink session exists for '${owner.localSurfaceId}'",
                    feature = owner.localSurfaceId,
                ),
            ),
        )

    private fun ownerPayload(owner: PhoneInkSurfaceOwner): JSONObject = JSONObject()
        .put("surfaceId", owner.wireSurfaceId)
        .put("localSurfaceId", owner.localSurfaceId)
        .put("ownerPluginId", owner.pluginId)

    private companion object {
        const val RESYNC_MIN_INTERVAL_MS = 1_000L
    }
}
