package com.anezium.rokidbus.plugin.assistant

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal const val TAKE_NOTE_TOOL_NAME = "take_note"
internal const val LIST_NOTES_TOOL_NAME = "list_notes"
internal const val SEARCH_NOTES_TOOL_NAME = "search_notes"
internal const val DELETE_NOTE_TOOL_NAME = "delete_note"
internal const val SET_REMINDER_TOOL_NAME = "set_reminder"
internal const val LIST_REMINDERS_TOOL_NAME = "list_reminders"
internal const val CANCEL_REMINDER_TOOL_NAME = "cancel_reminder"
internal const val SET_TIMER_TOOL_NAME = "set_timer"

internal val PRODUCTIVITY_TOOL_NAMES = setOf(
    TAKE_NOTE_TOOL_NAME,
    LIST_NOTES_TOOL_NAME,
    SEARCH_NOTES_TOOL_NAME,
    DELETE_NOTE_TOOL_NAME,
    SET_REMINDER_TOOL_NAME,
    LIST_REMINDERS_TOOL_NAME,
    CANCEL_REMINDER_TOOL_NAME,
    SET_TIMER_TOOL_NAME,
)

internal fun assistantProductivityTools(
    noteStore: AssistantNoteStore,
    reminderStore: AssistantReminderStore,
    reminderScheduler: AssistantReminderScheduler,
    epochClock: () -> Long = System::currentTimeMillis,
    elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    zoneId: () -> ZoneId = { ZoneId.systemDefault() },
): List<AssistantToolDefinition> = listOf(
    TakeNoteTool(noteStore),
    ListNotesTool(noteStore, zoneId),
    SearchNotesTool(noteStore, zoneId),
    DeleteNoteTool(noteStore),
    SetReminderTool(reminderStore, reminderScheduler, epochClock),
    ListRemindersTool(reminderStore, epochClock, elapsedClock),
    CancelReminderTool(reminderStore, reminderScheduler),
    SetTimerTool(
        store = reminderStore,
        scheduler = reminderScheduler,
        epochClock = epochClock,
        elapsedClock = elapsedClock,
        zoneId = zoneId,
    ),
)

internal abstract class TextAssistantTool : AssistantToolDefinition {
    override fun isAvailable(context: AssistantToolAvailabilityContext): Boolean =
        context.provider.supportsTools && context.session.active
}

internal class TakeNoteTool(
    private val store: AssistantNoteStore,
) : TextAssistantTool() {
    override val name = TAKE_NOTE_TOOL_NAME
    override val description = "Save a private note for the user. Text may be shortened to 2000 characters."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"text":{"type":"string","minLength":1},"title":{"type":["string","null"],"maxLength":80}},"required":["text","title"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val executionFailureCode = "note_save_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("text", "title"))
            ?: return AssistantToolValidation.Invalid()
        val text = arguments.stringOrNull("text")
            ?.takeIf(String::isNotBlank)
            ?: return AssistantToolValidation.Invalid()
        if (arguments.has("title") && !arguments.isNull("title") && arguments.stringOrNull("title") == null) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(arguments)
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        when (
            val result = store.save(
                text = arguments.getString("text"),
                title = arguments.stringOrNull("title"),
            )
        ) {
            AssistantNoteSaveResult.Full -> AssistantToolResult.Error("notes_full")
            is AssistantNoteSaveResult.Saved -> AssistantToolResult.Json(
                JSONObject()
                    .put("id", result.note.id)
                    .put("title", result.note.title)
                    .put("saved", true)
                    .put("truncated", result.textTruncated)
                    .toString(),
            )
        }
    }
}

internal class ListNotesTool(
    private val store: AssistantNoteStore,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = LIST_NOTES_TOOL_NAME
    override val description = "List up to 10 most recent saved notes."
    override val parametersSchema = EMPTY_PARAMETERS_SCHEMA
    override val sideEffecting = false

    override fun validate(argumentsJson: String): AssistantToolValidation = emptyArguments(argumentsJson)

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val notes = store.notes()
        AssistantToolResult.Json(
            JSONObject()
                .put("notes", JSONArray().apply {
                    notes.take(MAX_TOOL_RESULTS).forEach { note -> put(note.toResultJson(zoneId())) }
                })
                .put("total", notes.size)
                .toString(),
        )
    }
}

internal class SearchNotesTool(
    private val store: AssistantNoteStore,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = SEARCH_NOTES_TOOL_NAME
    override val description = "Search saved note titles and text with a case-insensitive substring."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":200}},"required":["query"],"additionalProperties":false}""",
    )
    override val sideEffecting = false

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("query"))
            ?: return AssistantToolValidation.Invalid()
        return if (arguments.stringOrNull("query")?.isNotBlank() == true) {
            AssistantToolValidation.Valid(arguments)
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val query = arguments.getString("query").trim()
        val matches = store.search(query)
        AssistantToolResult.Json(
            JSONObject()
                .put("notes", JSONArray().apply {
                    matches.take(MAX_TOOL_RESULTS).forEach { note ->
                        put(note.toResultJson(zoneId()).put("matched_snippet", note.matchedSnippet(query)))
                    }
                })
                .put("total", matches.size)
                .toString(),
        )
    }
}

internal class DeleteNoteTool(
    private val store: AssistantNoteStore,
) : TextAssistantTool() {
    override val name = DELETE_NOTE_TOOL_NAME
    override val description = "Delete one saved note by its exact id."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"id":{"type":"string","pattern":"^n_[a-z0-9]{8}$"}},"required":["id"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val executionFailureCode = "note_delete_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("id"))
            ?: return AssistantToolValidation.Invalid()
        return if (NOTE_ID.matches(arguments.stringOrNull("id").orEmpty())) {
            AssistantToolValidation.Valid(arguments)
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        if (store.delete(arguments.getString("id"))) {
            AssistantToolResult.Json(JSONObject().put("deleted", true).toString())
        } else {
            AssistantToolResult.Error("note_not_found")
        }
    }
}

internal class SetReminderTool(
    private val store: AssistantReminderStore,
    private val scheduler: AssistantReminderScheduler,
    private val epochClock: () -> Long = System::currentTimeMillis,
) : TextAssistantTool() {
    override val name = SET_REMINDER_TOOL_NAME
    override val description =
        "Schedule a reminder at an absolute ISO-8601 local date-time with a UTC offset."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"time":{"type":"string"},"label":{"type":"string","minLength":1,"maxLength":200}},"required":["time","label"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val executionFailureCode = "reminder_save_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("time", "label"))
            ?: return AssistantToolValidation.Invalid()
        val label = arguments.stringOrNull("label")
        val scheduled = arguments.stringOrNull("time")?.let(::parseOffsetDateTime)
        if (label.isNullOrBlank() || scheduled == null) return AssistantToolValidation.Invalid()
        val delta = scheduled.toInstant().toEpochMilli() - epochClock()
        return when {
            delta < -CLOCK_SKEW_TOLERANCE_MS ->
                AssistantToolValidation.Invalid(AssistantToolResult.Error("time_in_past"))
            delta > MAX_REMINDER_AHEAD_MS ->
                AssistantToolValidation.Invalid(AssistantToolResult.Error("time_too_far"))
            else -> AssistantToolValidation.Valid(arguments)
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val now = epochClock()
        val scheduled = checkNotNull(parseOffsetDateTime(arguments.getString("time")))
        val scheduledIso = scheduled.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        when (
            val saved = store.save(
                label = arguments.getString("label"),
                epochMillis = scheduled.toInstant().toEpochMilli(),
                originalIso = arguments.getString("time"),
                createdAtMs = now,
                kind = AssistantReminderKind.REMINDER,
            )
        ) {
            AssistantReminderSaveResult.Full -> AssistantToolResult.Error("reminders_full")
            is AssistantReminderSaveResult.Saved -> {
                val outcome = try {
                    scheduler.schedule(saved.reminder)
                } catch (error: Throwable) {
                    store.delete(saved.reminder.id)
                    throw error
                }
                AssistantToolResult.Json(
                    JSONObject()
                        .put("id", saved.reminder.id)
                        .put("label", saved.reminder.label)
                        .put("scheduled_for", scheduledIso)
                        .put("in_minutes", minutesUntil(saved.reminder.epochMillis - now))
                        .apply { if (!outcome.exact) put("exact", false) }
                        .toString(),
                )
            }
        }
    }
}

internal class ListRemindersTool(
    private val store: AssistantReminderStore,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) : TextAssistantTool() {
    override val name = LIST_REMINDERS_TOOL_NAME
    override val description = "List up to 10 pending reminders and timers, soonest first."
    override val parametersSchema = EMPTY_PARAMETERS_SCHEMA
    override val sideEffecting = false

    override fun validate(argumentsJson: String): AssistantToolValidation = emptyArguments(argumentsJson)

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val nowEpoch = epochClock()
        val nowElapsed = elapsedClock()
        val reminders = store.pending().sortedBy { reminder ->
            reminder.remainingMillis(nowEpoch, nowElapsed)
        }
        AssistantToolResult.Json(
            JSONObject()
                .put("reminders", JSONArray().apply {
                    reminders.take(MAX_TOOL_RESULTS).forEach { reminder ->
                        put(
                            JSONObject()
                                .put("id", reminder.id)
                                .put("label", reminder.label)
                                .put("scheduled_for", reminder.originalIso)
                                .put(
                                    "in_minutes",
                                    minutesUntil(reminder.remainingMillis(nowEpoch, nowElapsed)),
                                ),
                        )
                    }
                })
                .put("total", reminders.size)
                .toString(),
        )
    }
}

internal class CancelReminderTool(
    private val store: AssistantReminderStore,
    private val scheduler: AssistantReminderScheduler,
) : TextAssistantTool() {
    override val name = CANCEL_REMINDER_TOOL_NAME
    override val description = "Cancel a pending reminder by exact id or a unique case-insensitive label."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"id_or_label":{"type":"string","minLength":1,"maxLength":200}},"required":["id_or_label"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val executionFailureCode = "reminder_cancel_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("id_or_label"))
            ?: return AssistantToolValidation.Invalid()
        return if (arguments.stringOrNull("id_or_label")?.isNotBlank() == true) {
            AssistantToolValidation.Valid(arguments)
        } else {
            AssistantToolValidation.Invalid()
        }
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        when (val result = store.cancel(arguments.getString("id_or_label"))) {
            AssistantReminderCancelResult.NotFound -> AssistantToolResult.Error("reminder_not_found")
            is AssistantReminderCancelResult.Ambiguous -> AssistantToolResult.Error(
                code = "ambiguous",
                detailsJson = JSONObject()
                    .put("candidates", JSONArray().apply {
                        result.candidates.forEach { candidate ->
                            put(
                                JSONObject()
                                    .put("id", candidate.id)
                                    .put("label", candidate.label)
                                    .put("scheduled_for", candidate.originalIso),
                            )
                        }
                    })
                    .toString(),
            )
            is AssistantReminderCancelResult.Cancelled -> {
                runCatching { scheduler.cancel(result.reminder.id) }
                AssistantToolResult.Json(
                    JSONObject()
                        .put("cancelled", true)
                        .put("id", result.reminder.id)
                        .toString(),
                )
            }
        }
    }
}

internal class SetTimerTool(
    private val store: AssistantReminderStore,
    private val scheduler: AssistantReminderScheduler,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = SET_TIMER_TOOL_NAME
    override val description = "Start a 5-second to 24-hour timer using a duration in whole seconds."
    override val parametersSchema = schema(
        """{"type":"object","properties":{"duration_seconds":{"type":"integer","minimum":5,"maximum":86400},"label":{"type":["string","null"],"maxLength":200}},"required":["duration_seconds","label"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val executionFailureCode = "timer_save_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictArguments(argumentsJson, setOf("duration_seconds", "label"))
            ?: return AssistantToolValidation.Invalid()
        val seconds = arguments.wholeLongOrNull("duration_seconds")
        if (seconds !in MIN_TIMER_SECONDS..MAX_TIMER_SECONDS) {
            return AssistantToolValidation.Invalid(AssistantToolResult.Error("bad_duration"))
        }
        if (arguments.has("label") && !arguments.isNull("label") && arguments.stringOrNull("label") == null) {
            return AssistantToolValidation.Invalid()
        }
        return AssistantToolValidation.Valid(arguments)
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        val seconds = checkNotNull(arguments.wholeLongOrNull("duration_seconds"))
        val nowEpoch = epochClock()
        val fireEpoch = nowEpoch + seconds * MILLIS_PER_SECOND
        val firesAt = Instant.ofEpochMilli(fireEpoch)
            .atZone(zoneId())
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        when (
            val saved = store.save(
                label = arguments.stringOrNull("label")?.takeIf(String::isNotBlank) ?: "Timer",
                epochMillis = fireEpoch,
                originalIso = firesAt,
                createdAtMs = nowEpoch,
                kind = AssistantReminderKind.TIMER,
                elapsedRealtimeDeadlineMs = elapsedClock() + seconds * MILLIS_PER_SECOND,
            )
        ) {
            AssistantReminderSaveResult.Full -> AssistantToolResult.Error("reminders_full")
            is AssistantReminderSaveResult.Saved -> {
                val outcome = try {
                    scheduler.schedule(saved.reminder)
                } catch (error: Throwable) {
                    store.delete(saved.reminder.id)
                    throw error
                }
                AssistantToolResult.Json(
                    JSONObject()
                        .put("id", saved.reminder.id)
                        .put("label", saved.reminder.label)
                        .put("fires_at", firesAt)
                        .put("in_seconds", seconds)
                        .apply { if (!outcome.exact) put("exact", false) }
                        .toString(),
                )
            }
        }
    }
}

private val EMPTY_PARAMETERS_SCHEMA = schema(
    """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
)
private const val MAX_TOOL_RESULTS = 10
private const val PREVIEW_CHARS = 120
private const val MATCH_CONTEXT_CHARS = 50
private const val CLOCK_SKEW_TOLERANCE_MS = 60_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_REMINDER_AHEAD_MS = 365L * 24L * 60L * 60L * MILLIS_PER_SECOND
private const val MIN_TIMER_SECONDS = 5L
private const val MAX_TIMER_SECONDS = 24L * 60L * 60L
private val NOTE_ID = Regex("n_[a-z0-9]{8}")

private fun schema(text: String) = AssistantToolJsonSchema(text)

private fun emptyArguments(argumentsJson: String): AssistantToolValidation {
    val arguments = strictArguments(argumentsJson, emptySet())
        ?: return AssistantToolValidation.Invalid()
    return AssistantToolValidation.Valid(arguments)
}

private fun strictArguments(argumentsJson: String, allowedNames: Set<String>): JSONObject? {
    val arguments = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
        ?: return null
    val names = mutableSetOf<String>()
    val keys = arguments.keys()
    while (keys.hasNext()) names += keys.next()
    return arguments.takeIf { names.all(allowedNames::contains) }
}

private fun JSONObject.stringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else opt(name) as? String

private fun JSONObject.wholeLongOrNull(name: String): Long? {
    val number = opt(name) as? Number ?: return null
    val double = number.toDouble()
    if (!double.isFinite() || double % 1.0 != 0.0) return null
    return runCatching { number.toString().toBigDecimal().longValueExact() }.getOrNull()
}

private fun parseOffsetDateTime(value: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()

private fun AssistantNote.toResultJson(zoneId: ZoneId): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("preview", text.replace(Regex("\\s+"), " ").truncateAtWordBoundary(PREVIEW_CHARS))
    .put(
        "created_at",
        Instant.ofEpochMilli(createdAtMs)
            .atZone(zoneId)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

private fun AssistantNote.matchedSnippet(query: String): String {
    val source = if (title.contains(query, ignoreCase = true)) title else text
    val matchIndex = source.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val start = (matchIndex - MATCH_CONTEXT_CHARS).coerceAtLeast(0)
    val end = (start + PREVIEW_CHARS).coerceAtMost(source.length)
    val snippet = source.substring(start, end).replace(Regex("\\s+"), " ").trim()
    return buildString {
        if (start > 0) append('…')
        append(snippet)
        if (end < source.length) append('…')
    }
}

private fun AssistantReminder.remainingMillis(nowEpoch: Long, nowElapsed: Long): Long =
    if (kind == AssistantReminderKind.TIMER && elapsedRealtimeDeadlineMs != null) {
        elapsedRealtimeDeadlineMs - nowElapsed
    } else {
        epochMillis - nowEpoch
    }

private fun minutesUntil(remainingMillis: Long): Long =
    ceil(remainingMillis.coerceAtLeast(0L).toDouble() / 60_000.0).toLong()
