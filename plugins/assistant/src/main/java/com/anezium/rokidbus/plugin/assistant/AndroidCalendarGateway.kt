package com.anezium.rokidbus.plugin.assistant

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

internal class AndroidCalendarGateway(
    context: Context,
) : AssistantCalendarGateway {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun canReadCalendar(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override fun canWriteCalendar(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override fun calendars(): List<AssistantCalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            CalendarContract.Calendars._ID + " ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
            val primaryColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            val visibleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            val accessColumn = cursor.getColumnIndexOrThrow(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            )
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        AssistantCalendarInfo(
                            id = cursor.getLong(idColumn),
                            displayName = cursor.getString(nameColumn).orEmpty(),
                            isPrimary = cursor.getInt(primaryColumn) == 1,
                            visible = cursor.getInt(visibleColumn) == 1,
                            canWrite = cursor.getInt(accessColumn) >=
                                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun createEvent(event: AssistantCalendarEventWrite): Boolean {
        val eventInsert = ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
            .withValue(CalendarContract.Events.CALENDAR_ID, event.calendarId)
            .withValue(CalendarContract.Events.TITLE, event.title)
            .withValue(CalendarContract.Events.DTSTART, event.startMillis)
            .withValue(CalendarContract.Events.DTEND, event.endMillis)
            .withValue(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            .withValue(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
            .withValue(CalendarContract.Events.EVENT_LOCATION, event.location)
            .withValue(CalendarContract.Events.DESCRIPTION, event.description)
            .withValue(
                CalendarContract.Events.HAS_ALARM,
                if (event.reminderMinutesBefore == null) 0 else 1,
            )
            .build()
        val operations = arrayListOf(eventInsert)
        event.reminderMinutesBefore?.let { minutes ->
            operations += ContentProviderOperation
                .newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                .withValue(CalendarContract.Reminders.MINUTES, minutes)
                .withValue(
                    CalendarContract.Reminders.METHOD,
                    CalendarContract.Reminders.METHOD_ALERT,
                )
                .build()
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return results.firstOrNull()?.uri?.let(ContentUris::parseId) != null
    }

    override fun deleteEvent(
        eventId: Long,
        expectedTitle: String,
        expectedStartMillis: Long,
        expectedAllDay: Boolean,
        deleteRecurringSeries: Boolean,
    ): AssistantCalendarDeleteResult {
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val event = resolver.query(
            eventUri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE,
                CalendarContract.Events.EXRULE,
                CalendarContract.Events.EXDATE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CalendarEventSnapshot(
                rawTitle = cursor.getString(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE),
                ),
                startMillis = cursor.getLong(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART),
                ),
                allDay = cursor.getInt(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY),
                ) == 1,
                recurrenceRule = cursor.getString(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE),
                ),
                recurrenceDates = cursor.getString(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.RDATE),
                ),
                exceptionRule = cursor.getString(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.EXRULE),
                ),
                exceptionDates = cursor.getString(
                    cursor.getColumnIndexOrThrow(CalendarContract.Events.EXDATE),
                ),
            )
        } ?: return AssistantCalendarDeleteResult.NOT_FOUND

        if (event.displayTitle != expectedTitle || event.allDay != expectedAllDay) {
            return AssistantCalendarDeleteResult.IDENTITY_MISMATCH
        }
        if (!event.recurring && event.startMillis != expectedStartMillis) {
            return AssistantCalendarDeleteResult.IDENTITY_MISMATCH
        }
        if (!hasExactInstance(eventId, expectedTitle, expectedStartMillis, expectedAllDay)) {
            return AssistantCalendarDeleteResult.IDENTITY_MISMATCH
        }
        if (event.recurring && !deleteRecurringSeries) {
            return AssistantCalendarDeleteResult.RECURRING_SERIES_CONFIRMATION_REQUIRED
        }
        val guard = event.deleteGuard()
        return when (resolver.delete(eventUri, guard.selection, guard.arguments)) {
            1 -> AssistantCalendarDeleteResult.DELETED
            0 -> AssistantCalendarDeleteResult.IDENTITY_MISMATCH
            else -> AssistantCalendarDeleteResult.FAILED
        }
    }

    private fun hasExactInstance(
        eventId: Long,
        expectedTitle: String,
        expectedStartMillis: Long,
        expectedAllDay: Boolean,
    ): Boolean {
        val endMillis = runCatching { Math.addExact(expectedStartMillis, 1L) }
            .getOrNull()
            ?: return false
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, expectedStartMillis)
            ContentUris.appendId(this, endMillis)
        }.build()
        return resolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
            ),
            CalendarContract.Instances.EVENT_ID + "=?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val startColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val titleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val allDayColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            var matched = false
            while (!matched && cursor.moveToNext()) {
                matched = cursor.getLong(idColumn) == eventId &&
                    cursor.getLong(startColumn) == expectedStartMillis &&
                    cursor.getString(titleColumn)
                        .orEmpty()
                        .ifBlank { "Untitled event" } == expectedTitle &&
                    (cursor.getInt(allDayColumn) == 1) == expectedAllDay
            }
            matched
        } == true
    }

    override fun instances(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
    ): List<AssistantCalendarInstance> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMillis)
            ContentUris.appendId(this, endMillis)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE,
        )
        return resolver.query(
            uri,
            projection,
            CalendarContract.Calendars.VISIBLE + "=1",
            null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { cursor ->
            val eventIdColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val startColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val titleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val locationColumn = cursor.getColumnIndexOrThrow(
                CalendarContract.Instances.EVENT_LOCATION,
            )
            val allDayColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val recurrenceColumns = listOf(
                CalendarContract.Instances.RRULE,
                CalendarContract.Instances.RDATE,
            ).map(cursor::getColumnIndexOrThrow)
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    add(
                        AssistantCalendarInstance(
                            eventId = cursor.getLong(eventIdColumn),
                            startMillis = cursor.getLong(startColumn),
                            title = cursor.getString(titleColumn).orEmpty(),
                            location = cursor.getString(locationColumn),
                            allDay = cursor.getInt(allDayColumn) == 1,
                            recurring = recurrenceColumns.any { column ->
                                !cursor.getString(column).isNullOrBlank()
                            },
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private data class CalendarEventSnapshot(
        val rawTitle: String?,
        val startMillis: Long,
        val allDay: Boolean,
        val recurrenceRule: String?,
        val recurrenceDates: String?,
        val exceptionRule: String?,
        val exceptionDates: String?,
    ) {
        val displayTitle: String = rawTitle.orEmpty().ifBlank { "Untitled event" }
        val recurring: Boolean = !recurrenceRule.isNullOrBlank() || !recurrenceDates.isNullOrBlank()

        fun deleteGuard(): CalendarDeleteGuard {
            val clauses = mutableListOf<String>()
            val arguments = mutableListOf<String>()
            clauses.addExactOrNull(CalendarContract.Events.TITLE, rawTitle, arguments)
            clauses += CalendarContract.Events.DTSTART + "=?"
            arguments += startMillis.toString()
            clauses += CalendarContract.Events.ALL_DAY + "=?"
            arguments += if (allDay) "1" else "0"
            clauses.addExactOrNull(CalendarContract.Events.RRULE, recurrenceRule, arguments)
            clauses.addExactOrNull(CalendarContract.Events.RDATE, recurrenceDates, arguments)
            clauses.addExactOrNull(CalendarContract.Events.EXRULE, exceptionRule, arguments)
            clauses.addExactOrNull(CalendarContract.Events.EXDATE, exceptionDates, arguments)
            return CalendarDeleteGuard(clauses.joinToString(" AND "), arguments.toTypedArray())
        }
    }

    private data class CalendarDeleteGuard(
        val selection: String,
        val arguments: Array<String>,
    )

}

private fun MutableList<String>.addExactOrNull(
    column: String,
    value: String?,
    arguments: MutableList<String>,
) {
    if (value == null) {
        this += "$column IS NULL"
    } else {
        this += "$column=?"
        arguments += value
    }
}
