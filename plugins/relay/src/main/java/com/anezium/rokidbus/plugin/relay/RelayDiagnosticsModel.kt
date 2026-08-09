package com.anezium.rokidbus.plugin.relay

import java.util.ArrayDeque

internal enum class RelayObservationPath {
    NONE,
    ADDRESS,
    ASSOCIATION_ID,
}

internal enum class RelayRepairResult {
    NEVER,
    NO_ACCESS,
    HEALTHY,
    WAITING,
    BACKING_OFF,
    REBIND_REQUESTED,
    CLEAN_STATIC_REQUESTED,
    CLEAN_INSTANCE_REQUESTED,
    REBIND_REPEATED,
    CONNECTED,
    CLEAN_RATE_LIMITED,
    FAILED,
    COMMAND_FAILED,
}

internal enum class RelayDiagnosticState {
    GUARDIAN,
    LISTENER,
    REPAIR,
    PROCESS_EXIT,
    PROCESS_START,
    COMPANION,
    OBSERVATION,
}

internal enum class RelayDiagnosticReason {
    GUARDIAN_CREATED,
    GUARDIAN_BOUND,
    GUARDIAN_UNBOUND,
    GUARDIAN_DESTROYED,
    LISTENER_CONNECTED,
    LISTENER_DISCONNECTED,
    LISTENER_DESTROYED,
    LISTENER_RESET_AFTER_PROCESS_START,
    NO_ACCESS,
    HEALTHY,
    WAITING,
    BACKING_OFF,
    REBIND_REQUESTED,
    CLEAN_STATIC_REQUESTED,
    CLEAN_INSTANCE_REQUESTED,
    REBIND_REPEATED,
    CONNECTED,
    CLEAN_RATE_LIMITED,
    REPAIR_FAILED,
    COMMAND_FAILED,
    PROCESS_EXIT_RECORDED,
    PROCESS_START_RECORDED,
    PROCESS_START_FORCE_STOPPED,
    PROCESS_SNAPSHOT_FAILED,
    COMPANION_APPEARED,
    COMPANION_DISAPPEARED,
    COMPANION_SERVICE_BOUND,
    COMPANION_SERVICE_UNBOUND,
    OBSERVATION_ADDRESS_REGISTERED,
    OBSERVATION_ASSOCIATION_ID_REGISTERED,
    OBSERVATION_REGISTRATION_FAILED,
}

internal data class RelayDiagnosticEvent(
    val wallTimeMs: Long,
    val state: RelayDiagnosticState,
    val reason: RelayDiagnosticReason,
    val counter: Long = 0L,
    val generation: Long = 0L,
)

internal object RelayRepairDiagnosticsPolicy {
    fun resultAfterDecision(
        current: RelayRepairResult,
        decision: RelayRepairDecision,
    ): RelayRepairResult = when {
        decision.action != RelayRepairAction.NONE -> decision.result
        decision.result == RelayRepairResult.CONNECTED -> decision.result
        decision.result == RelayRepairResult.CLEAN_RATE_LIMITED -> decision.result
        decision.result == RelayRepairResult.FAILED -> decision.result
        decision.result == RelayRepairResult.COMMAND_FAILED -> decision.result
        else -> current
    }

    fun shouldAppend(previous: RelayDiagnosticEvent?, next: RelayDiagnosticEvent): Boolean {
        if (previous == null) return true
        return previous.reason != next.reason ||
            previous.counter != next.counter ||
            previous.generation != next.generation
    }
}

internal class RelayDiagnosticRing(
    private val capacity: Int,
    initialEvents: List<RelayDiagnosticEvent> = emptyList(),
) {
    private val events = ArrayDeque<RelayDiagnosticEvent>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
        initialEvents.takeLast(capacity).forEach(events::addLast)
    }

    fun append(event: RelayDiagnosticEvent) {
        while (events.size >= capacity) events.removeFirst()
        events.addLast(event)
    }

    fun snapshot(): List<RelayDiagnosticEvent> = events.toList()
}

internal interface RelayDiagnosticsPersistence {
    fun load(): RelayDiagnosticsSnapshot

    fun save(snapshot: RelayDiagnosticsSnapshot)
}

internal class RelayDiagnosticsRepository(
    private val persistence: RelayDiagnosticsPersistence,
    capacity: Int = DEFAULT_EVENT_CAPACITY,
) {
    private var current = persistence.load()
    private val ring = RelayDiagnosticRing(capacity, current.events)

    @Synchronized
    fun snapshot(): RelayDiagnosticsSnapshot = current.copy(events = ring.snapshot())

    @Synchronized
    fun update(transform: (RelayDiagnosticsSnapshot) -> RelayDiagnosticsSnapshot) {
        current = transform(current).copy(events = ring.snapshot())
        persistence.save(current)
    }

    @Synchronized
    fun record(
        event: RelayDiagnosticEvent,
        transform: (RelayDiagnosticsSnapshot) -> RelayDiagnosticsSnapshot = { it },
    ) {
        ring.append(event)
        current = transform(current).copy(events = ring.snapshot())
        persistence.save(current)
    }

    /**
     * Every phone notification lands here, so this update may not pay the
     * full-snapshot serialization on each call: the in-memory state is always
     * exact, but disk sees it at most once per [minSaveIntervalMs] — or with
     * whichever ordinary record()/update() comes first, since those persist
     * the same current snapshot anyway.
     */
    @Synchronized
    fun updateLazily(
        minSaveIntervalMs: Long,
        nowMs: Long,
        transform: (RelayDiagnosticsSnapshot) -> RelayDiagnosticsSnapshot,
    ) {
        current = transform(current).copy(events = ring.snapshot())
        if (nowMs - lastLazySaveMs in 0 until minSaveIntervalMs) return
        lastLazySaveMs = nowMs
        persistence.save(current)
    }

    private var lastLazySaveMs = Long.MIN_VALUE

    companion object {
        const val DEFAULT_EVENT_CAPACITY = 192
    }
}

internal data class RelayDiagnosticsSnapshot(
    val notificationAccessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val listenerConnectedSinceWallMs: Long = 0L,
    val listenerConnectGeneration: Long = 0L,
    val lastListenerDisconnectedWallMs: Long = 0L,
    val lastRawNotificationPostedWallMs: Long = 0L,
    val lastAcceptedCaptureWallMs: Long = 0L,
    val guardianBound: Boolean = false,
    val lastRepairAttemptWallMs: Long = 0L,
    val lastRepairResult: RelayRepairResult = RelayRepairResult.NEVER,
    val lastProcessExitReason: Int? = null,
    val lastProcessStartReason: Int? = null,
    val lastProcessStartType: Int? = null,
    val forceStoppedBeforeStart: Boolean? = null,
    val companionObservationRegistered: Boolean = false,
    val companionObservationPath: RelayObservationPath = RelayObservationPath.NONE,
    val companionServiceBound: Boolean = false,
    val repairState: RelayRepairState = RelayRepairState(),
    val events: List<RelayDiagnosticEvent> = emptyList(),
)

/** Only typed codes and numbers cross the persistence/export boundary. */
internal object RelayDiagnosticsRedactor {
    fun encodeEvents(events: List<RelayDiagnosticEvent>): String = events.joinToString("\n") { event ->
        listOf(
            event.wallTimeMs.coerceAtLeast(0L),
            event.state.name,
            event.reason.name,
            event.counter,
            event.generation,
        ).joinToString("|")
    }

    fun decodeEvents(value: String): List<RelayDiagnosticEvent> = value.lineSequence().mapNotNull { line ->
        val fields = line.split('|')
        if (fields.size != EVENT_FIELD_COUNT) return@mapNotNull null
        val wallTimeMs = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
        val state = enumValueOrNull<RelayDiagnosticState>(fields[1]) ?: return@mapNotNull null
        val reason = enumValueOrNull<RelayDiagnosticReason>(fields[2]) ?: return@mapNotNull null
        val counter = fields[3].toLongOrNull() ?: return@mapNotNull null
        val generation = fields[4].toLongOrNull() ?: return@mapNotNull null
        RelayDiagnosticEvent(wallTimeMs, state, reason, counter, generation)
    }.toList()

    fun export(snapshot: RelayDiagnosticsSnapshot): String = buildString {
        appendLine("relay_diagnostics_v1")
        appendLine("notification_access=${snapshot.notificationAccessGranted}")
        appendLine("listener_connected=${snapshot.listenerConnected}")
        appendLine("listener_connected_since_ms=${snapshot.listenerConnectedSinceWallMs}")
        appendLine("listener_generation=${snapshot.listenerConnectGeneration}")
        appendLine("last_listener_disconnected_ms=${snapshot.lastListenerDisconnectedWallMs}")
        appendLine("last_raw_callback_ms=${snapshot.lastRawNotificationPostedWallMs}")
        appendLine("last_accepted_capture_ms=${snapshot.lastAcceptedCaptureWallMs}")
        appendLine("guardian_bound=${snapshot.guardianBound}")
        appendLine("last_repair_attempt_ms=${snapshot.lastRepairAttemptWallMs}")
        appendLine("last_repair_result=${snapshot.lastRepairResult.name}")
        appendLine("last_process_exit_reason=${snapshot.lastProcessExitReason ?: UNKNOWN_NUMBER}")
        appendLine("last_process_start_reason=${snapshot.lastProcessStartReason ?: UNKNOWN_NUMBER}")
        appendLine("last_process_start_type=${snapshot.lastProcessStartType ?: UNKNOWN_NUMBER}")
        appendLine("force_stopped_before_start=${snapshot.forceStoppedBeforeStart?.toString() ?: UNKNOWN_VALUE}")
        appendLine("companion_observation_registered=${snapshot.companionObservationRegistered}")
        appendLine("companion_observation_path=${snapshot.companionObservationPath.name}")
        appendLine("companion_service_bound=${snapshot.companionServiceBound}")
        appendLine("events=${snapshot.events.size}")
        snapshot.events.forEach { event ->
            append("event=")
            append(event.wallTimeMs.coerceAtLeast(0L))
            append('|')
            append(event.state.name)
            append('|')
            append(event.reason.name)
            append('|')
            append(event.counter)
            append('|')
            appendLine(event.generation)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private const val EVENT_FIELD_COUNT = 5
    private const val UNKNOWN_NUMBER = -1
    private const val UNKNOWN_VALUE = "UNKNOWN"
}
