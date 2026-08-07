package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class AssistantReminderKind(val wireValue: String) {
    REMINDER("reminder"),
    TIMER("timer"),
    ;

    companion object {
        fun fromWireValue(value: String): AssistantReminderKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class AssistantReminder(
    val id: String,
    val label: String,
    val epochMillis: Long,
    val originalIso: String,
    val createdAtMs: Long,
    val kind: AssistantReminderKind,
    val elapsedRealtimeDeadlineMs: Long?,
)

sealed interface AssistantReminderSaveResult {
    data class Saved(val reminder: AssistantReminder) : AssistantReminderSaveResult
    data object Full : AssistantReminderSaveResult
}

sealed interface AssistantReminderCancelResult {
    data class Cancelled(val reminder: AssistantReminder) : AssistantReminderCancelResult
    data class Ambiguous(val candidates: List<AssistantReminder>) : AssistantReminderCancelResult
    data object NotFound : AssistantReminderCancelResult
}

/** Blocking, thread-safe pending-reminder persistence for delivery and the future settings UI. */
class AssistantReminderStore internal constructor(
    filesDir: File,
    private val maxPending: Int = MAX_PENDING_REMINDERS,
    private val idGenerator: () -> String = { assistantShortId(REMINDER_ID_PREFIX) },
    private val fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    private val logger: (String) -> Unit = {},
) {
    private val storeFile: File = File(filesDir, STORE_FILE_NAME)

    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        logger = { message -> Log.w(TAG, message) },
    )

    init {
        require(maxPending > 0)
    }

    fun pending(): List<AssistantReminder> = synchronized(fileLock) {
        readReminders().sortedBy(AssistantReminder::epochMillis)
    }

    fun reminder(id: String): AssistantReminder? = synchronized(fileLock) {
        readReminders().firstOrNull { it.id == id }
    }

    fun save(
        label: String,
        epochMillis: Long,
        originalIso: String,
        createdAtMs: Long,
        kind: AssistantReminderKind,
        elapsedRealtimeDeadlineMs: Long? = null,
    ): AssistantReminderSaveResult = synchronized(fileLock) {
        val reminders = readReminders()
        if (reminders.size >= maxPending) return@synchronized AssistantReminderSaveResult.Full
        val normalizedLabel = label.trim()
        require(normalizedLabel.isNotEmpty())
        require(kind != AssistantReminderKind.TIMER || elapsedRealtimeDeadlineMs != null)
        val reminder = AssistantReminder(
            id = uniqueId(reminders.asSequence().map(AssistantReminder::id).toSet()),
            label = normalizedLabel.take(MAX_LABEL_CHARS),
            epochMillis = epochMillis,
            originalIso = originalIso,
            createdAtMs = createdAtMs,
            kind = kind,
            elapsedRealtimeDeadlineMs = elapsedRealtimeDeadlineMs,
        )
        persist(reminders + reminder)
        AssistantReminderSaveResult.Saved(reminder)
    }

    fun delete(id: String): AssistantReminder? = synchronized(fileLock) {
        val reminders = readReminders()
        val removed = reminders.firstOrNull { it.id == id } ?: return@synchronized null
        persist(reminders.filterNot { it.id == id })
        removed
    }

    fun cancel(idOrLabel: String): AssistantReminderCancelResult = synchronized(fileLock) {
        val reminders = readReminders()
        val exactId = reminders.firstOrNull { it.id == idOrLabel }
        val matches = if (exactId == null) {
            reminders.filter { it.label.equals(idOrLabel.trim(), ignoreCase = true) }
        } else {
            listOf(exactId)
        }
        when (matches.size) {
            0 -> AssistantReminderCancelResult.NotFound
            1 -> {
                val removed = matches.single()
                persist(reminders.filterNot { it.id == removed.id })
                AssistantReminderCancelResult.Cancelled(removed)
            }
            else -> AssistantReminderCancelResult.Ambiguous(
                matches.sortedBy(AssistantReminder::epochMillis),
            )
        }
    }

    /** Atomically removes a reminder before delivery so duplicate alarms cannot double-notify. */
    fun takeForDelivery(id: String): AssistantReminder? = delete(id)

    private val fileLock: Any
        get() = FILE_LOCKS.computeIfAbsent(storeFile.absoluteFile.normalize().path) { Any() }

    private fun uniqueId(existing: Set<String>): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idGenerator()
            if (REMINDER_ID.matches(candidate) && candidate !in existing) return candidate
        }
        error("Could not allocate a unique reminder id.")
    }

    private fun readReminders(): List<AssistantReminder> {
        if (!storeFile.isFile) return emptyList()
        return runCatching {
            val values = JSONObject(storeFile.readText(Charsets.UTF_8)).getJSONArray(JSON_REMINDERS)
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    val id = value.getString(JSON_ID)
                    val kind = AssistantReminderKind.fromWireValue(value.getString(JSON_KIND))
                        ?: continue
                    if (!REMINDER_ID.matches(id)) continue
                    add(
                        AssistantReminder(
                            id = id,
                            label = value.getString(JSON_LABEL).take(MAX_LABEL_CHARS),
                            epochMillis = value.getLong(JSON_EPOCH_MILLIS),
                            originalIso = value.getString(JSON_ORIGINAL_ISO),
                            createdAtMs = value.getLong(JSON_CREATED_AT_MS),
                            kind = kind,
                            elapsedRealtimeDeadlineMs = if (value.has(JSON_ELAPSED_DEADLINE_MS)) {
                                value.getLong(JSON_ELAPSED_DEADLINE_MS)
                            } else {
                                null
                            },
                        ),
                    )
                }
            }.takeLast(maxPending)
        }.onFailure { error ->
            logger("Assistant reminder store read failed: ${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    private fun persist(reminders: List<AssistantReminder>) {
        val root = JSONObject()
            .put(JSON_VERSION, STORE_VERSION)
            .put(
                JSON_REMINDERS,
                JSONArray().apply {
                    reminders.forEach { reminder ->
                        put(
                            JSONObject()
                                .put(JSON_ID, reminder.id)
                                .put(JSON_LABEL, reminder.label)
                                .put(JSON_EPOCH_MILLIS, reminder.epochMillis)
                                .put(JSON_ORIGINAL_ISO, reminder.originalIso)
                                .put(JSON_CREATED_AT_MS, reminder.createdAtMs)
                                .put(JSON_KIND, reminder.kind.wireValue)
                                .apply {
                                    reminder.elapsedRealtimeDeadlineMs?.let { deadline ->
                                        put(JSON_ELAPSED_DEADLINE_MS, deadline)
                                    }
                                },
                        )
                    }
                },
            )
        writeAssistantJsonAtomically(storeFile, root.toString(), fileOperations)
    }

    companion object {
        internal const val STORE_FILE_NAME = "assistant_reminders_v1.json"
        const val MAX_LABEL_CHARS = 200
        private const val MAX_PENDING_REMINDERS = 50
        private const val MAX_ID_ATTEMPTS = 100
        private const val REMINDER_ID_PREFIX = "r_"
        private const val STORE_VERSION = 1
        private const val TAG = "NexusAssistant"
        private const val JSON_VERSION = "version"
        private const val JSON_REMINDERS = "reminders"
        private const val JSON_ID = "id"
        private const val JSON_LABEL = "label"
        private const val JSON_EPOCH_MILLIS = "epochMillis"
        private const val JSON_ORIGINAL_ISO = "originalIso"
        private const val JSON_CREATED_AT_MS = "createdAtMs"
        private const val JSON_KIND = "kind"
        private const val JSON_ELAPSED_DEADLINE_MS = "elapsedRealtimeDeadlineMs"
        private val REMINDER_ID = Regex("r_[a-z0-9]{8}")
        private val FILE_LOCKS = ConcurrentHashMap<String, Any>()
    }
}
