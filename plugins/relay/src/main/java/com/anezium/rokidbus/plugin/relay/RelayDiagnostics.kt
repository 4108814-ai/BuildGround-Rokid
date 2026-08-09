package com.anezium.rokidbus.plugin.relay

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

internal object RelayDiagnostics {
    @Volatile
    private var repository: RelayDiagnosticsRepository? = null
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    fun snapshot(context: Context): RelayDiagnosticsSnapshot {
        val stored = repository(context).snapshot()
        val live = NotificationControl.isListenerConnected()
        return stored.copy(
            notificationAccessGranted = notificationAccessGranted(context),
            listenerConnected = live,
            listenerConnectedSinceWallMs = if (live) stored.listenerConnectedSinceWallMs else 0L,
        )
    }

    fun export(context: Context): String = RelayDiagnosticsRedactor.export(snapshot(context))

    fun observe(observer: () -> Unit): () -> Unit {
        observers += observer
        return { observers -= observer }
    }

    @Synchronized
    fun recordGuardianCreated(context: Context, listenerLive: Boolean) {
        val now = System.currentTimeMillis()
        repository(context).record(
            RelayDiagnosticEvent(now, RelayDiagnosticState.GUARDIAN, RelayDiagnosticReason.GUARDIAN_CREATED),
        ) { current ->
            current.copy(
                guardianBound = false,
                listenerConnected = listenerLive,
                listenerConnectedSinceWallMs = if (listenerLive) {
                    current.listenerConnectedSinceWallMs
                } else {
                    0L
                },
            )
        }
    }

    @Synchronized
    fun recordGuardianBound(context: Context, bound: Boolean) {
        val reason = if (bound) {
            RelayDiagnosticReason.GUARDIAN_BOUND
        } else {
            RelayDiagnosticReason.GUARDIAN_UNBOUND
        }
        repository(context).record(
            RelayDiagnosticEvent(System.currentTimeMillis(), RelayDiagnosticState.GUARDIAN, reason),
        ) { it.copy(guardianBound = bound) }
    }

    @Synchronized
    fun recordGuardianDestroyed(context: Context) {
        repository(context).record(
            RelayDiagnosticEvent(
                System.currentTimeMillis(),
                RelayDiagnosticState.GUARDIAN,
                RelayDiagnosticReason.GUARDIAN_DESTROYED,
            ),
        ) { it.copy(guardianBound = false) }
    }

    @Synchronized
    fun recordListenerConnected(context: Context): Long {
        val repo = repository(context)
        val current = repo.snapshot()
        val generation = if (current.listenerConnectGeneration == Long.MAX_VALUE) {
            1L
        } else {
            current.listenerConnectGeneration + 1L
        }
        val now = System.currentTimeMillis()
        repo.record(
            RelayDiagnosticEvent(
                now,
                RelayDiagnosticState.LISTENER,
                RelayDiagnosticReason.LISTENER_CONNECTED,
                generation = generation,
            ),
        ) {
            it.copy(
                listenerConnected = true,
                listenerConnectedSinceWallMs = now,
                listenerConnectGeneration = generation,
            )
        }
        return generation
    }

    @Synchronized
    fun recordListenerDisconnected(context: Context) {
        val now = System.currentTimeMillis()
        val generation = repository(context).snapshot().listenerConnectGeneration
        repository(context).record(
            RelayDiagnosticEvent(
                now,
                RelayDiagnosticState.LISTENER,
                RelayDiagnosticReason.LISTENER_DISCONNECTED,
                generation = generation,
            ),
        ) {
            it.copy(
                listenerConnected = false,
                listenerConnectedSinceWallMs = 0L,
                lastListenerDisconnectedWallMs = now,
            )
        }
    }

    @Synchronized
    fun recordListenerDestroyed(context: Context) {
        val generation = repository(context).snapshot().listenerConnectGeneration
        repository(context).record(
            RelayDiagnosticEvent(
                System.currentTimeMillis(),
                RelayDiagnosticState.LISTENER,
                RelayDiagnosticReason.LISTENER_DESTROYED,
                generation = generation,
            ),
        ) {
            it.copy(listenerConnected = false, listenerConnectedSinceWallMs = 0L)
        }
    }

    @Synchronized
    fun recordRawNotificationPosted(context: Context) {
        val now = System.currentTimeMillis()
        repository(context).updateLazily(HOT_PATH_SAVE_INTERVAL_MS, now) {
            it.copy(lastRawNotificationPostedWallMs = now)
        }
    }

    @Synchronized
    fun recordAcceptedCapture(context: Context) {
        val now = System.currentTimeMillis()
        repository(context).updateLazily(HOT_PATH_SAVE_INTERVAL_MS, now) {
            it.copy(lastAcceptedCaptureWallMs = now)
        }
    }

    @Synchronized
    fun recordAccessState(context: Context, granted: Boolean) {
        repository(context).update { it.copy(notificationAccessGranted = granted) }
    }

    @Synchronized
    fun recordRepairDecision(
        context: Context,
        decision: RelayRepairDecision,
        connectGeneration: Long,
    ) {
        val now = System.currentTimeMillis()
        val reason = when (decision.result) {
            RelayRepairResult.NEVER -> return
            RelayRepairResult.NO_ACCESS -> RelayDiagnosticReason.NO_ACCESS
            RelayRepairResult.HEALTHY -> RelayDiagnosticReason.HEALTHY
            RelayRepairResult.WAITING -> RelayDiagnosticReason.WAITING
            RelayRepairResult.BACKING_OFF -> RelayDiagnosticReason.BACKING_OFF
            RelayRepairResult.REBIND_REQUESTED -> RelayDiagnosticReason.REBIND_REQUESTED
            RelayRepairResult.CLEAN_STATIC_REQUESTED -> RelayDiagnosticReason.CLEAN_STATIC_REQUESTED
            RelayRepairResult.CLEAN_INSTANCE_REQUESTED -> RelayDiagnosticReason.CLEAN_INSTANCE_REQUESTED
            RelayRepairResult.REBIND_REPEATED -> RelayDiagnosticReason.REBIND_REPEATED
            RelayRepairResult.CONNECTED -> RelayDiagnosticReason.CONNECTED
            RelayRepairResult.CLEAN_RATE_LIMITED -> RelayDiagnosticReason.CLEAN_RATE_LIMITED
            RelayRepairResult.FAILED -> RelayDiagnosticReason.REPAIR_FAILED
            RelayRepairResult.COMMAND_FAILED -> RelayDiagnosticReason.COMMAND_FAILED
        }
        val event = RelayDiagnosticEvent(
            now,
            RelayDiagnosticState.REPAIR,
            reason,
            counter = decision.state.failureCount.toLong(),
            generation = connectGeneration,
        )
        val isAttempt = decision.action != RelayRepairAction.NONE
        val updateSnapshot: (RelayDiagnosticsSnapshot) -> RelayDiagnosticsSnapshot = {
            it.copy(
                lastRepairAttemptWallMs = if (isAttempt) now else it.lastRepairAttemptWallMs,
                lastRepairResult = RelayRepairDiagnosticsPolicy.resultAfterDecision(
                    it.lastRepairResult,
                    decision,
                ),
                repairState = decision.state,
            )
        }
        val diagnostics = repository(context)
        val previous = diagnostics.snapshot().events.lastOrNull {
            it.state == RelayDiagnosticState.REPAIR
        }
        if (RelayRepairDiagnosticsPolicy.shouldAppend(previous, event)) {
            diagnostics.record(event, updateSnapshot)
        } else {
            diagnostics.update(updateSnapshot)
        }
    }

    @Synchronized
    fun recordRepairCommandFailed(context: Context, state: RelayRepairState, connectGeneration: Long) {
        val decision = RelayRepairDecision(
            RelayRepairAction.NONE,
            RelayRepairResult.COMMAND_FAILED,
            state,
        )
        recordRepairDecision(context, decision, connectGeneration)
    }

    fun repairState(context: Context): RelayRepairState = repository(context).snapshot().repairState

    @Synchronized
    fun recordProcessExit(
        context: Context,
        timestampMs: Long,
        reason: Int,
        status: Int,
        latest: Boolean,
    ) {
        repository(context).record(
            RelayDiagnosticEvent(
                timestampMs.coerceAtLeast(0L),
                RelayDiagnosticState.PROCESS_EXIT,
                RelayDiagnosticReason.PROCESS_EXIT_RECORDED,
                counter = reason.toLong(),
                generation = status.toLong(),
            ),
        ) {
            if (latest) it.copy(lastProcessExitReason = reason) else it
        }
    }

    @Synchronized
    fun recordProcessStart(
        context: Context,
        reason: Int,
        startType: Int,
        forceStopped: Boolean,
        latest: Boolean,
    ) {
        val eventReason = if (forceStopped) {
            RelayDiagnosticReason.PROCESS_START_FORCE_STOPPED
        } else {
            RelayDiagnosticReason.PROCESS_START_RECORDED
        }
        repository(context).record(
            RelayDiagnosticEvent(
                System.currentTimeMillis(),
                RelayDiagnosticState.PROCESS_START,
                eventReason,
                counter = reason.toLong(),
                generation = startType.toLong(),
            ),
        ) {
            if (latest) {
                it.copy(
                    lastProcessStartReason = reason,
                    lastProcessStartType = startType,
                    forceStoppedBeforeStart = forceStopped,
                )
            } else {
                it
            }
        }
    }

    @Synchronized
    fun clearCurrentProcessStart(context: Context) {
        repository(context).update {
            it.copy(
                lastProcessStartReason = null,
                lastProcessStartType = null,
                forceStoppedBeforeStart = null,
            )
        }
    }

    @Synchronized
    fun recordProcessSnapshotFailure(context: Context, state: RelayDiagnosticState) {
        repository(context).record(
            RelayDiagnosticEvent(
                System.currentTimeMillis(),
                state,
                RelayDiagnosticReason.PROCESS_SNAPSHOT_FAILED,
            ),
        )
    }

    @Synchronized
    fun recordCompanionObservation(
        context: Context,
        path: RelayObservationPath,
        registered: Boolean,
    ) {
        val reason = when {
            !registered -> RelayDiagnosticReason.OBSERVATION_REGISTRATION_FAILED
            path == RelayObservationPath.ASSOCIATION_ID -> {
                RelayDiagnosticReason.OBSERVATION_ASSOCIATION_ID_REGISTERED
            }
            else -> RelayDiagnosticReason.OBSERVATION_ADDRESS_REGISTERED
        }
        repository(context).record(
            RelayDiagnosticEvent(System.currentTimeMillis(), RelayDiagnosticState.OBSERVATION, reason),
        ) {
            it.copy(
                companionObservationRegistered = registered,
                companionObservationPath = path,
            )
        }
    }

    @Synchronized
    fun recordCompanionPresence(context: Context, appeared: Boolean) {
        val reason = if (appeared) {
            RelayDiagnosticReason.COMPANION_APPEARED
        } else {
            RelayDiagnosticReason.COMPANION_DISAPPEARED
        }
        repository(context).record(
            RelayDiagnosticEvent(System.currentTimeMillis(), RelayDiagnosticState.COMPANION, reason),
        )
    }

    @Synchronized
    fun recordCompanionServiceBound(context: Context, bound: Boolean) {
        val reason = if (bound) {
            RelayDiagnosticReason.COMPANION_SERVICE_BOUND
        } else {
            RelayDiagnosticReason.COMPANION_SERVICE_UNBOUND
        }
        repository(context).record(
            RelayDiagnosticEvent(System.currentTimeMillis(), RelayDiagnosticState.COMPANION, reason),
        ) { it.copy(companionServiceBound = bound) }
    }

    private fun notificationAccessGranted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val component = ComponentName(context, RelayNotificationListener::class.java)
        return try {
            manager.isNotificationListenerAccessGranted(component)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun repository(context: Context): RelayDiagnosticsRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: RelayDiagnosticsRepository(
                SharedPreferencesRelayDiagnosticsPersistence(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                    ::notifyObservers,
                ),
            ).also { created ->
                created.record(
                    RelayDiagnosticEvent(
                        System.currentTimeMillis(),
                        RelayDiagnosticState.LISTENER,
                        RelayDiagnosticReason.LISTENER_RESET_AFTER_PROCESS_START,
                    ),
                ) {
                    it.copy(
                        listenerConnected = false,
                        listenerConnectedSinceWallMs = 0L,
                        guardianBound = false,
                        companionServiceBound = false,
                    )
                }
                repository = created
            }
        }
    }

    private fun notifyObservers() {
        observers.forEach { observer ->
            try {
                observer()
            } catch (_: RuntimeException) {
                // Diagnostics recording must not depend on a settings screen observer.
            }
        }
    }

    private const val PREFS = "relay_diagnostics"

    /** The listener sees every notification on the phone; disk must not (issue #11). */
    private const val HOT_PATH_SAVE_INTERVAL_MS = 60_000L
}

private class SharedPreferencesRelayDiagnosticsPersistence(
    private val preferences: SharedPreferences,
    private val onSaved: () -> Unit,
) : RelayDiagnosticsPersistence {
    override fun load(): RelayDiagnosticsSnapshot = RelayDiagnosticsSnapshot(
        notificationAccessGranted = preferences.getBoolean(KEY_ACCESS, false),
        listenerConnected = preferences.getBoolean(KEY_LISTENER_CONNECTED, false),
        listenerConnectedSinceWallMs = preferences.getLong(KEY_CONNECTED_SINCE, 0L),
        listenerConnectGeneration = preferences.getLong(KEY_CONNECT_GENERATION, 0L),
        lastListenerDisconnectedWallMs = preferences.getLong(KEY_LAST_DISCONNECTED, 0L),
        lastRawNotificationPostedWallMs = preferences.getLong(KEY_LAST_RAW, 0L),
        lastAcceptedCaptureWallMs = preferences.getLong(KEY_LAST_ACCEPTED, 0L),
        guardianBound = preferences.getBoolean(KEY_GUARDIAN_BOUND, false),
        lastRepairAttemptWallMs = preferences.getLong(KEY_LAST_REPAIR_ATTEMPT, 0L),
        lastRepairResult = enumValueOrDefault(
            preferences.getString(KEY_LAST_REPAIR_RESULT, null),
            RelayRepairResult.NEVER,
        ),
        lastProcessExitReason = nullableInt(KEY_LAST_EXIT_REASON),
        lastProcessStartReason = nullableInt(KEY_LAST_START_REASON),
        lastProcessStartType = nullableInt(KEY_LAST_START_TYPE),
        forceStoppedBeforeStart = nullableBoolean(KEY_FORCE_STOPPED),
        companionObservationRegistered = preferences.getBoolean(KEY_OBSERVATION_REGISTERED, false),
        companionObservationPath = enumValueOrDefault(
            preferences.getString(KEY_OBSERVATION_PATH, null),
            RelayObservationPath.NONE,
        ),
        companionServiceBound = preferences.getBoolean(KEY_COMPANION_BOUND, false),
        repairState = RelayRepairState(
            phase = enumValueOrDefault(
                preferences.getString(KEY_REPAIR_PHASE, null),
                RelayRepairPhase.IDLE,
            ),
            expectedGeneration = preferences.getLong(KEY_REPAIR_EXPECTED_GENERATION, 0L),
            deadlineUptimeMs = preferences.getLong(KEY_REPAIR_DEADLINE, 0L),
            failureCount = preferences.getInt(KEY_REPAIR_FAILURE_COUNT, 0).coerceAtLeast(0),
            nextAttemptUptimeMs = preferences.getLong(KEY_REPAIR_NEXT_ATTEMPT, 0L),
            lastCleanUptimeMs = preferences.getLong(
                KEY_REPAIR_LAST_CLEAN,
                RelayRepairState.NO_UPTIME,
            ),
            lastObservedUptimeMs = preferences.getLong(KEY_REPAIR_LAST_OBSERVED, 0L),
        ),
        events = RelayDiagnosticsRedactor.decodeEvents(preferences.getString(KEY_EVENTS, "").orEmpty()),
    )

    override fun save(snapshot: RelayDiagnosticsSnapshot) {
        preferences.edit()
            .putBoolean(KEY_ACCESS, snapshot.notificationAccessGranted)
            .putBoolean(KEY_LISTENER_CONNECTED, snapshot.listenerConnected)
            .putLong(KEY_CONNECTED_SINCE, snapshot.listenerConnectedSinceWallMs)
            .putLong(KEY_CONNECT_GENERATION, snapshot.listenerConnectGeneration)
            .putLong(KEY_LAST_DISCONNECTED, snapshot.lastListenerDisconnectedWallMs)
            .putLong(KEY_LAST_RAW, snapshot.lastRawNotificationPostedWallMs)
            .putLong(KEY_LAST_ACCEPTED, snapshot.lastAcceptedCaptureWallMs)
            .putBoolean(KEY_GUARDIAN_BOUND, snapshot.guardianBound)
            .putLong(KEY_LAST_REPAIR_ATTEMPT, snapshot.lastRepairAttemptWallMs)
            .putString(KEY_LAST_REPAIR_RESULT, snapshot.lastRepairResult.name)
            .putInt(KEY_LAST_EXIT_REASON, snapshot.lastProcessExitReason ?: NULL_INT)
            .putInt(KEY_LAST_START_REASON, snapshot.lastProcessStartReason ?: NULL_INT)
            .putInt(KEY_LAST_START_TYPE, snapshot.lastProcessStartType ?: NULL_INT)
            .putInt(KEY_FORCE_STOPPED, nullableBooleanValue(snapshot.forceStoppedBeforeStart))
            .putBoolean(KEY_OBSERVATION_REGISTERED, snapshot.companionObservationRegistered)
            .putString(KEY_OBSERVATION_PATH, snapshot.companionObservationPath.name)
            .putBoolean(KEY_COMPANION_BOUND, snapshot.companionServiceBound)
            .putString(KEY_REPAIR_PHASE, snapshot.repairState.phase.name)
            .putLong(KEY_REPAIR_EXPECTED_GENERATION, snapshot.repairState.expectedGeneration)
            .putLong(KEY_REPAIR_DEADLINE, snapshot.repairState.deadlineUptimeMs)
            .putInt(KEY_REPAIR_FAILURE_COUNT, snapshot.repairState.failureCount)
            .putLong(KEY_REPAIR_NEXT_ATTEMPT, snapshot.repairState.nextAttemptUptimeMs)
            .putLong(KEY_REPAIR_LAST_CLEAN, snapshot.repairState.lastCleanUptimeMs)
            .putLong(KEY_REPAIR_LAST_OBSERVED, snapshot.repairState.lastObservedUptimeMs)
            .putString(KEY_EVENTS, RelayDiagnosticsRedactor.encodeEvents(snapshot.events))
            .apply()
        onSaved()
    }

    private fun nullableInt(key: String): Int? = preferences.getInt(key, NULL_INT).takeUnless { it == NULL_INT }

    private fun nullableBoolean(key: String): Boolean? = when (preferences.getInt(key, NULL_BOOLEAN)) {
        FALSE_BOOLEAN -> false
        TRUE_BOOLEAN -> true
        else -> null
    }

    private fun nullableBooleanValue(value: Boolean?): Int = when (value) {
        false -> FALSE_BOOLEAN
        true -> TRUE_BOOLEAN
        null -> NULL_BOOLEAN
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_LISTENER_CONNECTED = "listener_connected"
        const val KEY_CONNECTED_SINCE = "connected_since"
        const val KEY_CONNECT_GENERATION = "connect_generation"
        const val KEY_LAST_DISCONNECTED = "last_disconnected"
        const val KEY_LAST_RAW = "last_raw"
        const val KEY_LAST_ACCEPTED = "last_accepted"
        const val KEY_GUARDIAN_BOUND = "guardian_bound"
        const val KEY_LAST_REPAIR_ATTEMPT = "last_repair_attempt"
        const val KEY_LAST_REPAIR_RESULT = "last_repair_result"
        const val KEY_LAST_EXIT_REASON = "last_exit_reason"
        const val KEY_LAST_START_REASON = "last_start_reason"
        const val KEY_LAST_START_TYPE = "last_start_type"
        const val KEY_FORCE_STOPPED = "force_stopped"
        const val KEY_OBSERVATION_REGISTERED = "observation_registered"
        const val KEY_OBSERVATION_PATH = "observation_path"
        const val KEY_COMPANION_BOUND = "companion_bound"
        const val KEY_REPAIR_PHASE = "repair_phase"
        const val KEY_REPAIR_EXPECTED_GENERATION = "repair_expected_generation"
        const val KEY_REPAIR_DEADLINE = "repair_deadline"
        const val KEY_REPAIR_FAILURE_COUNT = "repair_failure_count"
        const val KEY_REPAIR_NEXT_ATTEMPT = "repair_next_attempt"
        const val KEY_REPAIR_LAST_CLEAN = "repair_last_clean"
        const val KEY_REPAIR_LAST_OBSERVED = "repair_last_observed"
        const val KEY_EVENTS = "events"
        const val NULL_INT = Int.MIN_VALUE
        const val NULL_BOOLEAN = -1
        const val FALSE_BOOLEAN = 0
        const val TRUE_BOOLEAN = 1
    }
}
