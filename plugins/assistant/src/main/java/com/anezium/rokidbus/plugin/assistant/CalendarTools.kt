package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val CREATE_CALENDAR_EVENT_TOOL_NAME = "create_calendar_event"
internal const val LIST_CALENDAR_EVENTS_TOOL_NAME = "list_calendar_events"
internal const val TOOL_ERROR_CALENDAR_PERMISSION_REQUIRED = "calendar_permission_required"

internal val CALENDAR_TOOL_NAMES = setOf(
    CREATE_CALENDAR_EVENT_TOOL_NAME,
    LIST_CALENDAR_EVENTS_TOOL_NAME,
)

internal data class AssistantCalendarInfo(
    val id: Long,
    val displayName: String,
    val isPrimary: Boolean,
    val visible: Boolean,
    val canWrite: Boolean,
)

internal data class AssistantCalendarEventWrite(
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
    val location: String?,
    val description: String?,
    val reminderMinutesBefore: Int?,
)

internal data class AssistantCalendarInstance(
    val startMillis: Long,
    val title: String,
    val location: String?,
    val allDay: Boolean,
)

internal interface AssistantCalendarGateway {
    fun canReadCalendar(): Boolean

    fun canWriteCalendar(): Boolean

    fun calendars(): List<AssistantCalendarInfo>

    fun createEvent(event: AssistantCalendarEventWrite): Boolean

    fun instances(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<AssistantCalendarInstance>
}

internal fun assistantCalendarTools(
    gateway: AssistantCalendarGateway,
    epochClock: () -> Long = System::currentTimeMillis,
    zoneId: () -> ZoneId = { ZoneId.systemDefault() },
): List<AssistantToolDefinition> = listOf(
    CreateCalendarEventTool(gateway, zoneId),
    ListCalendarEventsTool(gateway, epochClock, zoneId),
)

internal class CreateCalendarEventTool(
    private val gateway: AssistantCalendarGateway,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = CREATE_CALENDAR_EVENT_TOOL_NAME
    override val description =
        "Create an appointment or event in the phone's real calendar. Use set_reminder " +
            "instead for a Nexus glasses reminder. Always use this tool when the user asks " +
            "to add, schedule, or book a calendar event. Timed start/end values are ISO-8601 " +
            "date-times; phone-local values and explicit UTC offsets are accepted. All-day " +
            "values are dates and end is exclusive."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"title":{"type":"string","minLength":1},"start":{"type":"string","description":"ISO-8601 local or offset date-time; use a date for an all-day event"},"end":{"type":["string","null"],"description":"ISO-8601 local or offset date-time; for all-day events this is an exclusive date"},"duration_minutes":{"type":["integer","null"],"minimum":1,"default":60},"all_day":{"type":["boolean","null"],"default":false},"location":{"type":["string","null"]},"description":{"type":["string","null"]},"reminder_minutes_before":{"type":["integer","null"],"minimum":0}},"required":["title","start","end","duration_minutes","all_day","location","description","reminder_minutes_before"],"additionalProperties":false}""",
    )
    override val sideEffecting = true
    override val progressLabel = "Adding to calendar"
    override val executionFailureCode = "calendar_event_create_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation =
        when (val parsed = parseCalendarEvent(argumentsJson, zoneId())) {
            is CalendarEventParseResult.Invalid -> AssistantToolValidation.Invalid(parsed.error)
            is CalendarEventParseResult.Valid -> AssistantToolValidation.Valid(parsed.arguments)
        }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        if (!gateway.canReadCalendar() || !gateway.canWriteCalendar()) {
            return@withContext calendarPermissionError()
        }
        val calendar = selectWritableCalendar(gateway.calendars())
            ?: return@withContext AssistantToolResult.Error(
                code = "no_writable_calendar",
                detailsJson = JSONObject()
                    .put(
                        "message",
                        "No writable phone calendar is available. Add or enable a calendar " +
                            "account with write access.",
                    )
                    .toString(),
            )
        val event = AssistantCalendarEventWrite(
            calendarId = calendar.id,
            title = arguments.getString(NORMALIZED_TITLE),
            startMillis = arguments.getLong(NORMALIZED_START_MILLIS),
            endMillis = arguments.getLong(NORMALIZED_END_MILLIS),
            allDay = arguments.getBoolean(NORMALIZED_ALL_DAY),
            timeZone = arguments.getString(NORMALIZED_TIME_ZONE),
            location = arguments.nullableString(NORMALIZED_LOCATION),
            description = arguments.nullableString(NORMALIZED_DESCRIPTION),
            reminderMinutesBefore = arguments.nullableInt(NORMALIZED_REMINDER_MINUTES),
        )
        if (!gateway.createEvent(event)) {
            return@withContext AssistantToolResult.Error(executionFailureCode)
        }
        AssistantToolResult.Json(
            JSONObject()
                .put("created", true)
                .put("title", event.title)
                .put("when", event.humanReadableStart(zoneId()))
                .put("calendar", calendar.displayName.ifBlank { "Calendar" })
                .toString(),
        )
    }
}

internal class ListCalendarEventsTool(
    private val gateway: AssistantCalendarGateway,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) : TextAssistantTool() {
    override val name = LIST_CALENDAR_EVENTS_TOOL_NAME
    override val description =
        "List up to 50 appointments and events from the phone's real calendar over the " +
            "next 1 to 31 days. This does not list Nexus glasses reminders. Returned timed " +
            "start values are already phone-local wall times; present them unchanged and do " +
            "not apply a UTC offset."
    override val parametersSchema = AssistantToolJsonSchema(
        """{"type":"object","properties":{"days":{"type":["integer","null"],"minimum":1,"maximum":31,"default":7}},"required":["days"],"additionalProperties":false}""",
    )
    override val sideEffecting = false
    override val progressLabel = "Checking calendar"
    override val executionFailureCode = "calendar_events_list_failed"

    override fun validate(argumentsJson: String): AssistantToolValidation {
        val arguments = strictCalendarArguments(argumentsJson, setOf("days"))
            ?: return AssistantToolValidation.Invalid()
        val requestedDays = if (!arguments.has("days") || arguments.isNull("days")) {
            DEFAULT_CALENDAR_DAYS.toLong()
        } else {
            arguments.wholeLong("days")
                ?: return AssistantToolValidation.Invalid(
                    AssistantToolResult.Error("invalid_calendar_days"),
                )
        }
        if (requestedDays <= 0L) {
            return AssistantToolValidation.Invalid(
                AssistantToolResult.Error("invalid_calendar_days"),
            )
        }
        return AssistantToolValidation.Valid(
            JSONObject().put("days", requestedDays.coerceAtMost(MAX_CALENDAR_DAYS.toLong())),
        )
    }

    override suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult = withContext(Dispatchers.IO) {
        if (!gateway.canReadCalendar()) return@withContext calendarPermissionError()

        val days = arguments.getInt("days")
        val zone = zoneId()
        val startMillis = epochClock()
        val endMillis = Instant.ofEpochMilli(startMillis)
            .atZone(zone)
            .plusDays(days.toLong())
            .toInstant()
            .toEpochMilli()
        val instances = gateway.instances(startMillis, endMillis, MAX_CALENDAR_INSTANCES + 1)
        val visibleInstances = instances.take(MAX_CALENDAR_INSTANCES)
        AssistantToolResult.Json(
            JSONObject()
                .put("range_days", days)
                .put("time_zone", zone.id)
                .put("events", JSONArray().apply {
                    visibleInstances.forEach { instance -> put(instance.toResultJson(zone)) }
                })
                .put("count", visibleInstances.size)
                .apply {
                    if (instances.size > MAX_CALENDAR_INSTANCES) put("truncated", true)
                }
                .toString(),
        )
    }
}

internal fun selectWritableCalendar(
    calendars: List<AssistantCalendarInfo>,
): AssistantCalendarInfo? =
    calendars.firstOrNull { calendar -> calendar.isPrimary && calendar.canWrite }
        ?: calendars.firstOrNull { calendar -> calendar.visible && calendar.canWrite }

private sealed interface CalendarEventParseResult {
    data class Valid(val arguments: JSONObject) : CalendarEventParseResult
    data class Invalid(val error: AssistantToolResult.Error) : CalendarEventParseResult
}

private fun parseCalendarEvent(
    argumentsJson: String,
    zoneId: ZoneId,
): CalendarEventParseResult {
    val arguments = strictCalendarArguments(argumentsJson, CALENDAR_EVENT_ARGUMENTS)
        ?: return invalidCalendarEvent()
    val title = arguments.optionalString("title")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return invalidCalendarEvent("calendar_title_required")
    val startText = arguments.optionalString("start")
        ?: return invalidCalendarEvent("invalid_calendar_start")
    val endText = when {
        !arguments.has("end") || arguments.isNull("end") -> null
        else -> arguments.optionalString("end")
            ?: return invalidCalendarEvent()
    }
    val allDay = when {
        !arguments.has("all_day") || arguments.isNull("all_day") -> false
        arguments.opt("all_day") is Boolean -> arguments.getBoolean("all_day")
        else -> return invalidCalendarEvent()
    }
    val durationSupplied = arguments.has("duration_minutes") &&
        !arguments.isNull("duration_minutes")
    val durationMinutes = if (durationSupplied) {
        arguments.wholeLong("duration_minutes")
            ?.takeIf { value -> value > 0L }
            ?: return invalidCalendarEvent("invalid_calendar_duration")
    } else {
        DEFAULT_CALENDAR_DURATION_MINUTES
    }
    if (allDay && durationSupplied) {
        return invalidCalendarEvent("invalid_calendar_duration")
    }
    val reminderMinutes = when {
        !arguments.has("reminder_minutes_before") ||
            arguments.isNull("reminder_minutes_before") -> null
        else -> arguments.wholeLong("reminder_minutes_before")
            ?.takeIf { value -> value in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return invalidCalendarEvent("invalid_calendar_reminder")
    }
    val location = arguments.checkedOptionalString("location")
        ?: return invalidCalendarEvent()
    val description = arguments.checkedOptionalString("description")
        ?: return invalidCalendarEvent()

    val timing = if (allDay) {
        parseAllDayTiming(startText, endText)
    } else {
        parseTimedTiming(startText, endText, durationMinutes, zoneId)
    }
    if (timing is CalendarTimingResult.Invalid) return invalidCalendarEvent(timing.code)
    timing as CalendarTimingResult.Valid

    return CalendarEventParseResult.Valid(
        JSONObject()
            .put(NORMALIZED_TITLE, title)
            .put(NORMALIZED_START_MILLIS, timing.startMillis)
            .put(NORMALIZED_END_MILLIS, timing.endMillis)
            .put(NORMALIZED_ALL_DAY, allDay)
            .put(NORMALIZED_TIME_ZONE, if (allDay) ZoneOffset.UTC.id else zoneId.id)
            .put(NORMALIZED_LOCATION, location.value ?: JSONObject.NULL)
            .put(NORMALIZED_DESCRIPTION, description.value ?: JSONObject.NULL)
            .put(NORMALIZED_REMINDER_MINUTES, reminderMinutes ?: JSONObject.NULL),
    )
}

private sealed interface CalendarTimingResult {
    data class Valid(val startMillis: Long, val endMillis: Long) : CalendarTimingResult
    data class Invalid(val code: String) : CalendarTimingResult
}

private fun parseAllDayTiming(
    startText: String,
    endText: String?,
): CalendarTimingResult {
    val start = parseLocalDate(startText)
        ?: return CalendarTimingResult.Invalid("invalid_calendar_start")
    val end = if (endText == null) {
        runCatching { start.plusDays(1) }.getOrNull()
            ?: return CalendarTimingResult.Invalid("invalid_calendar_end")
    } else {
        parseLocalDate(endText)
            ?: return CalendarTimingResult.Invalid("invalid_calendar_end")
    }
    if (!end.isAfter(start)) {
        return CalendarTimingResult.Invalid("calendar_end_not_after_start")
    }
    return CalendarTimingResult.Valid(
        start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
}

private fun parseTimedTiming(
    startText: String,
    endText: String?,
    durationMinutes: Long,
    zoneId: ZoneId,
): CalendarTimingResult {
    val start = parseTimedDateTime(startText, zoneId)
        ?: return CalendarTimingResult.Invalid("invalid_calendar_start")
    val endInstant = if (endText == null) {
        try {
            start.instant.plusSeconds(Math.multiplyExact(durationMinutes, 60L))
        } catch (_: DateTimeException) {
            return CalendarTimingResult.Invalid("invalid_calendar_duration")
        } catch (_: ArithmeticException) {
            return CalendarTimingResult.Invalid("invalid_calendar_duration")
        }
    } else {
        parseTimedDateTime(endText, zoneId)?.instant
            ?: return CalendarTimingResult.Invalid("invalid_calendar_end")
    }
    if (!endInstant.isAfter(start.instant)) {
        return CalendarTimingResult.Invalid("calendar_end_not_after_start")
    }
    return CalendarTimingResult.Valid(
        start.instant.toEpochMilli(),
        endInstant.toEpochMilli(),
    )
}

private data class ParsedTimedDateTime(val instant: Instant)

private fun parseTimedDateTime(value: String, zoneId: ZoneId): ParsedTimedDateTime? =
    runCatching {
        ParsedTimedDateTime(
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(zoneId)
                .toInstant(),
        )
    }.getOrNull() ?: runCatching {
        ParsedTimedDateTime(
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(),
        )
    }.getOrNull()

private data class CheckedOptionalString(val value: String?)

private fun JSONObject.checkedOptionalString(name: String): CheckedOptionalString? = when {
    !has(name) || isNull(name) -> CheckedOptionalString(null)
    opt(name) is String -> CheckedOptionalString(getString(name).takeIf(String::isNotBlank))
    else -> null
}

private fun invalidCalendarEvent(
    code: String = TOOL_ERROR_INVALID_CALL,
): CalendarEventParseResult.Invalid =
    CalendarEventParseResult.Invalid(AssistantToolResult.Error(code))

private fun strictCalendarArguments(
    argumentsJson: String,
    allowedNames: Set<String>,
): JSONObject? {
    val arguments = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
        ?: return null
    val names = mutableSetOf<String>()
    val keys = arguments.keys()
    while (keys.hasNext()) names += keys.next()
    return arguments.takeIf { names.all(allowedNames::contains) }
}

private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else opt(name) as? String

private fun JSONObject.wholeLong(name: String): Long? {
    val number = opt(name) as? Number ?: return null
    val double = number.toDouble()
    if (!double.isFinite() || double % 1.0 != 0.0) return null
    return runCatching { number.toString().toBigDecimal().longValueExact() }.getOrNull()
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun JSONObject.nullableInt(name: String): Int? =
    if (isNull(name)) null else getInt(name)

private fun parseLocalDate(value: String): LocalDate? =
    runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()

private fun AssistantCalendarEventWrite.humanReadableStart(zoneId: ZoneId): String =
    if (allDay) {
        Instant.ofEpochMilli(startMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .format(HUMAN_DATE) + " (all day)"
    } else {
        Instant.ofEpochMilli(startMillis).atZone(zoneId).format(HUMAN_DATE_TIME)
    }

private fun AssistantCalendarInstance.toResultJson(zoneId: ZoneId): JSONObject =
    JSONObject()
        .put(
            "start",
            if (allDay) {
                Instant.ofEpochMilli(startMillis)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            } else {
                Instant.ofEpochMilli(startMillis)
                    .atZone(zoneId)
                    .toLocalDateTime()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            },
        )
        .put("title", title.ifBlank { "Untitled event" })
        .apply { location?.takeIf(String::isNotBlank)?.let { put("location", it) } }
        .put("all_day", allDay)

private fun calendarPermissionError(): AssistantToolResult.Error =
    AssistantToolResult.Error(
        code = TOOL_ERROR_CALENDAR_PERMISSION_REQUIRED,
        detailsJson = JSONObject()
            .put(
                "message",
                "Tell the user to open the Nexus Assistant app on the phone and grant " +
                    "Calendar access.",
            )
            .toString(),
    )

private const val DEFAULT_CALENDAR_DURATION_MINUTES = 60L
private const val DEFAULT_CALENDAR_DAYS = 7
private const val MAX_CALENDAR_DAYS = 31
private const val MAX_CALENDAR_INSTANCES = 50
private const val NORMALIZED_TITLE = "normalized_title"
private const val NORMALIZED_START_MILLIS = "normalized_start_millis"
private const val NORMALIZED_END_MILLIS = "normalized_end_millis"
private const val NORMALIZED_ALL_DAY = "normalized_all_day"
private const val NORMALIZED_TIME_ZONE = "normalized_time_zone"
private const val NORMALIZED_LOCATION = "normalized_location"
private const val NORMALIZED_DESCRIPTION = "normalized_description"
private const val NORMALIZED_REMINDER_MINUTES = "normalized_reminder_minutes"

private val CALENDAR_EVENT_ARGUMENTS = setOf(
    "title",
    "start",
    "end",
    "duration_minutes",
    "all_day",
    "location",
    "description",
    "reminder_minutes_before",
)
private val HUMAN_DATE = DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US)
private val HUMAN_DATE_TIME = DateTimeFormatter.ofPattern(
    "EEE, MMM d, uuuu 'at' h:mm a",
    Locale.US,
)
