package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

class AssistantMvpToolsTest {
    @Test
    fun `pipe 530x8 mass is deterministic`() = runTest {
        val result = EngineeringCalculatorTool().execute(
            AssistantToolCall("calc-1", ENGINEERING_CALCULATOR_TOOL_NAME, ""),
            JSONObject()
                .put("operation", "pipe_mass")
                .put("outer_diameter_mm", 530.0)
                .put("wall_mm", 8.0)
                .put("length_m", 1.0)
                .put("quantity", 1.0)
                .put("density_kg_m3", 7850.0),
        ) as AssistantToolResult.Json

        val json = JSONObject(result.text)
        assertEquals(102.986434, json.getDouble("kg_per_m"), 0.000001)
        assertEquals(0.102986, json.getDouble("total_t"), 0.000001)
    }

    @Test
    fun `cylinder volume returns total for quantity`() = runTest {
        val result = EngineeringCalculatorTool().execute(
            AssistantToolCall("calc-2", ENGINEERING_CALCULATOR_TOOL_NAME, ""),
            JSONObject()
                .put("operation", "cylinder_volume")
                .put("diameter_mm", 1200.0)
                .put("length_m", 38.0)
                .put("quantity", 2.0),
        ) as AssistantToolResult.Json

        val json = JSONObject(result.text)
        assertEquals(42.976988, json.getDouble("volume_each_m3"), 0.000001)
        assertEquals(85.953975, json.getDouble("total_m3"), 0.000001)
    }

    @Test
    fun `today brief combines calendar and reminders`() = runTest {
        val zone = ZoneId.of("Europe/Moscow")
        val now = LocalDateTime.of(2026, 8, 14, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val gateway = FakeTodayCalendarGateway(
            events = listOf(
                AssistantCalendarInstance(
                    eventId = 1L,
                    startMillis = LocalDateTime.of(2026, 8, 14, 12, 30)
                        .atZone(zone).toInstant().toEpochMilli(),
                    title = "Site meeting",
                    location = "Mosfilm",
                    allDay = false,
                    recurring = false,
                ),
            ),
        )
        val tempDir = Files.createTempDirectory("assistant-today-test").toFile()
        try {
            val reminders = AssistantReminderStore(tempDir)
            reminders.save(
                label = "Call supplier",
                epochMillis = LocalDateTime.of(2026, 8, 14, 15, 0)
                    .atZone(zone).toInstant().toEpochMilli(),
                originalIso = "2026-08-14T15:00:00+03:00",
                createdAtMs = now,
                kind = AssistantReminderKind.REMINDER,
            )
            val result = TodayBriefTool(
                calendarGateway = gateway,
                reminderStore = reminders,
                epochClock = { now },
                zoneId = { zone },
            ).execute(
                AssistantToolCall("today-1", TODAY_BRIEF_TOOL_NAME, ""),
                JSONObject(),
            ) as AssistantToolResult.Json

            val json = JSONObject(result.text)
            assertTrue(json.getBoolean("calendar_available"))
            assertEquals("Site meeting", json.getJSONArray("events").getJSONObject(0).getString("title"))
            assertEquals("Call supplier", json.getJSONArray("reminders").getJSONObject(0).getString("label"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private class FakeTodayCalendarGateway(
        private val events: List<AssistantCalendarInstance>,
    ) : AssistantCalendarGateway {
        override fun canReadCalendar(): Boolean = true
        override fun canWriteCalendar(): Boolean = true
        override fun calendars(): List<AssistantCalendarInfo> = emptyList()
        override fun createEvent(event: AssistantCalendarEventWrite): Boolean = true
        override fun deleteEvent(
            eventId: Long,
            expectedTitle: String,
            expectedStartMillis: Long,
            expectedAllDay: Boolean,
            deleteRecurringSeries: Boolean,
        ): AssistantCalendarDeleteResult = AssistantCalendarDeleteResult.NOT_FOUND

        override fun instances(
            startMillis: Long,
            endMillis: Long,
            limit: Int,
        ): List<AssistantCalendarInstance> = events
            .filter { it.startMillis in startMillis until endMillis }
            .take(limit)
    }
}
