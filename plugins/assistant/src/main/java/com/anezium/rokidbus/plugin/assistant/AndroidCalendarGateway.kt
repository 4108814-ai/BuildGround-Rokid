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
        deleteRecurringSeries: Boolean,
    ): AssistantCalendarDeleteResult {
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val event = resolver.query(
            eventUri,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val storedTitle = cursor.getString(
                cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE),
            ).orEmpty().ifBlank { "Untitled event" }
            val recurring = listOf(
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE,
            ).any { column ->
                !cursor.getString(cursor.getColumnIndexOrThrow(column)).isNullOrBlank()
            }
            storedTitle to recurring
        } ?: return AssistantCalendarDeleteResult.NOT_FOUND

        if (event.first != expectedTitle) return AssistantCalendarDeleteResult.TITLE_MISMATCH
        if (event.second && !deleteRecurringSeries) {
            return AssistantCalendarDeleteResult.RECURRING_SERIES_CONFIRMATION_REQUIRED
        }
        return if (resolver.delete(eventUri, null, null) == 1) {
            AssistantCalendarDeleteResult.DELETED
        } else {
            AssistantCalendarDeleteResult.FAILED
        }
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
}
