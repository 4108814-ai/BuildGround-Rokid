package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarToolsTest {
    @Test
    fun `create calendar event requires a non-blank title`() = runTest {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        assertEquals(
            AssistantToolResult.Error("calendar_title_required"),
            execute(tool, """{"start":"2026-08-10T09:00:00"}"""),
        )
        assertEquals(
            AssistantToolResult.Error("calendar_title_required"),
            execute(tool, """{"title":"   ","start":"2026-08-10T09:00:00"}"""),
        )
        assertTrue(gateway.createdEvents.isEmpty())
    }

    @Test
    fun `create calendar event rejects unparseable start and end`() = runTest {
        val tool = createTool(FakeCalendarGateway())

        assertEquals(
            AssistantToolResult.Error("invalid_calendar_start"),
            execute(tool, """{"title":"Planning","start":"tomorrow"}"""),
        )
        assertEquals(
            AssistantToolResult.Error("invalid_calendar_end"),
            execute(
                tool,
                """{"title":"Planning","start":"2026-08-10T09:00:00","end":"later"}""",
            ),
        )
    }

    @Test
    fun `create calendar event rejects an end that is not after the start`() = runTest {
        val tool = createTool(FakeCalendarGateway())

        val result = execute(
            tool,
            """{"title":"Planning","start":"2026-08-10T09:00:00","end":"2026-08-10T09:00:00"}""",
        )

        assertEquals(AssistantToolResult.Error("calendar_end_not_after_start"), result)
    }

    @Test
    fun `create calendar event rejects zero and negative durations`() = runTest {
        val tool = createTool(FakeCalendarGateway())

        assertEquals(
            AssistantToolResult.Error("invalid_calendar_duration"),
            execute(
                tool,
                """{"title":"Planning","start":"2026-08-10T09:00:00","duration_minutes":0}""",
            ),
        )
        assertEquals(
            AssistantToolResult.Error("invalid_calendar_duration"),
            execute(
                tool,
                """{"title":"Planning","start":"2026-08-10T09:00:00","duration_minutes":-1}""",
            ),
        )
    }

    @Test
    fun `all-day create rejects an explicit duration`() = runTest {
        val result = execute(
            createTool(FakeCalendarGateway()),
            """{"title":"Holiday","start":"2026-08-10","all_day":true,"duration_minutes":60}""",
        )

        assertEquals(AssistantToolResult.Error("invalid_calendar_duration"), result)
    }

    @Test
    fun `create calendar event rejects unknown argument names`() = runTest {
        val gateway = FakeCalendarGateway()

        val result = execute(
            createTool(gateway),
            """{"title":"Planning","start":"2026-08-10T09:00:00","private":true}""",
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), result)
        assertTrue(gateway.createdEvents.isEmpty())
    }

    @Test
    fun `create calendar event rejects a negative reminder offset`() = runTest {
        val result = execute(
            createTool(FakeCalendarGateway()),
            """{"title":"Planning","start":"2026-08-10T09:00:00","reminder_minutes_before":-5}""",
        )

        assertEquals(AssistantToolResult.Error("invalid_calendar_reminder"), result)
    }

    @Test
    fun `explicit end wins then duration and sixty minute defaults apply`() = runTest {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        execute(
            tool,
            """{"title":"Explicit","start":"2026-08-10T09:00:00","end":"2026-08-10T11:00:00","duration_minutes":15}""",
        )
        execute(
            tool,
            """{"title":"Duration","start":"2026-08-10T09:00:00","duration_minutes":45}""",
        )
        execute(
            tool,
            """{"title":"Default","start":"2026-08-10T09:00:00"}""",
        )

        assertEquals(epochAtParis(2026, 8, 10, 11, 0), gateway.createdEvents[0].endMillis)
        assertEquals(epochAtParis(2026, 8, 10, 9, 45), gateway.createdEvents[1].endMillis)
        assertEquals(epochAtParis(2026, 8, 10, 10, 0), gateway.createdEvents[2].endMillis)
    }

    @Test
    fun `all-day create uses UTC midnights and defaults to one day`() = runTest {
        val gateway = FakeCalendarGateway()

        execute(
            createTool(gateway),
            """{"title":"Holiday","start":"2026-08-10","all_day":true}""",
        )

        assertEquals(
            AssistantCalendarEventWrite(
                calendarId = 1L,
                title = "Holiday",
                startMillis = LocalDate.of(2026, 8, 10)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
                endMillis = LocalDate.of(2026, 8, 11)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
                allDay = true,
                timeZone = ZoneOffset.UTC.id,
                location = null,
                description = null,
                reminderMinutesBefore = null,
            ),
            gateway.createdEvents.single(),
        )
    }

    @Test
    fun `calendar selection prefers primary writable then first visible writable`() = runTest {
        val primaryGateway = FakeCalendarGateway(
            calendarResults = listOf(
                calendar(id = 2L, name = "Visible", primary = false),
                calendar(id = 3L, name = "Primary", primary = true, visible = false),
            ),
        )
        val fallbackGateway = FakeCalendarGateway(
            calendarResults = listOf(
                calendar(id = 4L, name = "Hidden", primary = false, visible = false),
                calendar(id = 5L, name = "First visible", primary = false),
                calendar(id = 6L, name = "Second visible", primary = false),
            ),
        )

        execute(
            createTool(primaryGateway),
            """{"title":"Primary","start":"2026-08-10T09:00:00"}""",
        )
        execute(
            createTool(fallbackGateway),
            """{"title":"Fallback","start":"2026-08-10T09:00:00"}""",
        )

        assertEquals(3L, primaryGateway.createdEvents.single().calendarId)
        assertEquals(5L, fallbackGateway.createdEvents.single().calendarId)
    }

    @Test
    fun `create calendar event reports when no writable calendar exists`() = runTest {
        val gateway = FakeCalendarGateway(
            calendarResults = listOf(
                calendar(id = 2L, name = "Read only", canWrite = false),
                calendar(id = 3L, name = "Hidden", primary = false, visible = false),
            ),
        )

        val result = execute(
            createTool(gateway),
            """{"title":"Planning","start":"2026-08-10T09:00:00"}""",
        ) as AssistantToolResult.Error

        assertEquals("no_writable_calendar", result.code)
        assertTrue(gateway.createdEvents.isEmpty())
    }

    @Test
    fun `create calendar event reports missing read or write permission with guidance`() = runTest {
        val missingRead = execute(
            createTool(FakeCalendarGateway(readAllowed = false)),
            """{"title":"Planning","start":"2026-08-10T09:00:00"}""",
        ) as AssistantToolResult.Error
        val missingWrite = execute(
            createTool(FakeCalendarGateway(writeAllowed = false)),
            """{"title":"Planning","start":"2026-08-10T09:00:00"}""",
        ) as AssistantToolResult.Error

        assertCalendarPermissionGuidance(missingRead)
        assertCalendarPermissionGuidance(missingWrite)
    }

    @Test
    fun `successful timed create sends normalized event and result`() = runTest {
        val gateway = FakeCalendarGateway(
            calendarResults = listOf(calendar(id = 42L, name = "Team")),
        )

        val result = execute(
            createTool(gateway),
            """{"title":" Planning ","start":"2026-08-10T09:30:00","end":"2026-08-10T10:45:00","location":"Room 4","description":"Quarterly plan","reminder_minutes_before":15}""",
        ) as AssistantToolResult.Json

        assertEquals(
            AssistantCalendarEventWrite(
                calendarId = 42L,
                title = "Planning",
                startMillis = epochAtParis(2026, 8, 10, 9, 30),
                endMillis = epochAtParis(2026, 8, 10, 10, 45),
                allDay = false,
                timeZone = PARIS_ZONE.id,
                location = "Room 4",
                description = "Quarterly plan",
                reminderMinutesBefore = 15,
            ),
            gateway.createdEvents.single(),
        )
        val json = JSONObject(result.text)
        assertTrue(json.getBoolean("created"))
        assertEquals("Planning", json.getString("title"))
        assertEquals("Mon, Aug 10, 2026 at 9:30 AM", json.getString("when"))
        assertEquals("Team", json.getString("calendar"))
    }

    @Test
    fun `list calendar events defaults to seven days and caps at thirty one`() = runTest {
        val gateway = FakeCalendarGateway()
        val tool = listTool(gateway)

        val defaultResult = execute(tool, "{}") as AssistantToolResult.Json
        val cappedResult = execute(tool, """{"days":90}""") as AssistantToolResult.Json

        assertEquals(7, JSONObject(defaultResult.text).getInt("range_days"))
        assertEquals(31, JSONObject(cappedResult.text).getInt("range_days"))
        assertEquals(
            InstanceRequest(NOW_EPOCH_MS, rangeEnd(7), 51),
            gateway.instanceRequests[0],
        )
        assertEquals(
            InstanceRequest(NOW_EPOCH_MS, rangeEnd(31), 51),
            gateway.instanceRequests[1],
        )
    }

    @Test
    fun `list calendar events rejects zero and negative days`() = runTest {
        val tool = listTool(FakeCalendarGateway())

        assertEquals(
            AssistantToolResult.Error("invalid_calendar_days"),
            execute(tool, """{"days":0}"""),
        )
        assertEquals(
            AssistantToolResult.Error("invalid_calendar_days"),
            execute(tool, """{"days":-1}"""),
        )
    }

    @Test
    fun `list calendar events reports missing permission with guidance`() = runTest {
        val result = execute(
            listTool(FakeCalendarGateway(readAllowed = false)),
            "{}",
        ) as AssistantToolResult.Error

        assertCalendarPermissionGuidance(result)
    }

    @Test
    fun `list calendar events formats all-day and timed instances`() = runTest {
        val gateway = FakeCalendarGateway(
            instanceResults = listOf(
                AssistantCalendarInstance(
                    startMillis = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli(),
                    title = "Holiday",
                    location = null,
                    allDay = true,
                ),
                AssistantCalendarInstance(
                    startMillis = epochAtParis(2026, 8, 10, 9, 30),
                    title = "",
                    location = "Room 4",
                    allDay = false,
                ),
                AssistantCalendarInstance(
                    startMillis = epochAtParis(2026, 8, 10, 11, 0),
                    title = "Remote",
                    location = "   ",
                    allDay = false,
                ),
            ),
        )

        val result = execute(listTool(gateway), "{}") as AssistantToolResult.Json
        val json = JSONObject(result.text)
        val events = json.getJSONArray("events")
        val allDay = events.getJSONObject(0)
        val timed = events.getJSONObject(1)
        val blankLocation = events.getJSONObject(2)

        assertEquals(3, json.getInt("count"))
        assertFalse(json.has("truncated"))
        assertEquals("2026-08-11", allDay.getString("start"))
        assertEquals("Holiday", allDay.getString("title"))
        assertTrue(allDay.getBoolean("all_day"))
        assertFalse(allDay.has("location"))
        assertEquals("Untitled event", timed.getString("title"))
        assertEquals("Room 4", timed.getString("location"))
        assertFalse(timed.getBoolean("all_day"))
        val timedStart = OffsetDateTime.parse(timed.getString("start"))
        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 30), timedStart.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(2), timedStart.offset)
        assertFalse(blankLocation.has("location"))
    }

    @Test
    fun `list calendar events returns fifty instances and marks truncation`() = runTest {
        val gateway = FakeCalendarGateway(
            instanceResults = (1..51).map { index ->
                AssistantCalendarInstance(
                    startMillis = NOW_EPOCH_MS + index * 60_000L,
                    title = "Event $index",
                    location = null,
                    allDay = false,
                )
            },
        )

        val result = execute(listTool(gateway), "{}") as AssistantToolResult.Json
        val json = JSONObject(result.text)
        val events = json.getJSONArray("events")

        assertEquals(50, json.getInt("count"))
        assertEquals(50, events.length())
        assertEquals("Event 50", events.getJSONObject(49).getString("title"))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(51, gateway.instanceRequests.single().limit)
    }

    private suspend fun execute(
        tool: AssistantToolDefinition,
        arguments: String,
    ): AssistantToolResult = AssistantToolRegistry(listOf(tool))
        .newExecutionPhase(NO_VISION)
        .execute(AssistantToolCall("call", tool.name, arguments))

    private fun createTool(gateway: AssistantCalendarGateway): CreateCalendarEventTool =
        CreateCalendarEventTool(gateway) { PARIS_ZONE }

    private fun listTool(gateway: AssistantCalendarGateway): ListCalendarEventsTool =
        ListCalendarEventsTool(
            gateway = gateway,
            epochClock = { NOW_EPOCH_MS },
            zoneId = { PARIS_ZONE },
        )

    private fun rangeEnd(days: Long): Long = Instant.ofEpochMilli(NOW_EPOCH_MS)
        .atZone(PARIS_ZONE)
        .plusDays(days)
        .toInstant()
        .toEpochMilli()

    private fun assertCalendarPermissionGuidance(error: AssistantToolResult.Error) {
        assertEquals(TOOL_ERROR_CALENDAR_PERMISSION_REQUIRED, error.code)
        assertEquals(
            "Tell the user to open the Nexus Assistant app on the phone and grant Calendar access.",
            JSONObject(error.detailsJson!!).getString("message"),
        )
    }

    private data class InstanceRequest(
        val startMillis: Long,
        val endMillis: Long,
        val limit: Int,
    )

    private class FakeCalendarGateway(
        private val readAllowed: Boolean = true,
        private val writeAllowed: Boolean = true,
        private val calendarResults: List<AssistantCalendarInfo> = listOf(calendar()),
        private val createSucceeds: Boolean = true,
        private val instanceResults: List<AssistantCalendarInstance> = emptyList(),
    ) : AssistantCalendarGateway {
        val createdEvents = mutableListOf<AssistantCalendarEventWrite>()
        val instanceRequests = mutableListOf<InstanceRequest>()

        override fun canReadCalendar(): Boolean = readAllowed

        override fun canWriteCalendar(): Boolean = writeAllowed

        override fun calendars(): List<AssistantCalendarInfo> = calendarResults

        override fun createEvent(event: AssistantCalendarEventWrite): Boolean {
            createdEvents += event
            return createSucceeds
        }

        override fun instances(
            startMillis: Long,
            endMillis: Long,
            limit: Int,
        ): List<AssistantCalendarInstance> {
            instanceRequests += InstanceRequest(startMillis, endMillis, limit)
            return instanceResults
        }
    }

    private companion object {
        val NO_VISION = AssistantProviderFeatures(supportsTools = true, supportsVision = false)
        val PARIS_ZONE: ZoneId = ZoneId.of("Europe/Paris")
        val NOW_EPOCH_MS: Long = Instant.parse("2026-08-10T10:00:00Z").toEpochMilli()

        fun calendar(
            id: Long = 1L,
            name: String = "Personal",
            primary: Boolean = true,
            visible: Boolean = true,
            canWrite: Boolean = true,
        ) = AssistantCalendarInfo(
            id = id,
            displayName = name,
            isPrimary = primary,
            visible = visible,
            canWrite = canWrite,
        )

        fun epochAtParis(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
            minute: Int,
        ): Long = LocalDateTime.of(year, month, day, hour, minute)
            .atZone(PARIS_ZONE)
            .toInstant()
            .toEpochMilli()
    }
}
