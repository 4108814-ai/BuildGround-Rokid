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
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )
        return resolver.query(
            uri,
            projection,
            CalendarContract.Calendars.VISIBLE + "=1",
            null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { cursor ->
            val startColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val titleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val locationColumn = cursor.getColumnIndexOrThrow(
                CalendarContract.Instances.EVENT_LOCATION,
            )
            val allDayColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    add(
                        AssistantCalendarInstance(
                            startMillis = cursor.getLong(startColumn),
                            title = cursor.getString(titleColumn).orEmpty(),
                            location = cursor.getString(locationColumn),
                            allDay = cursor.getInt(allDayColumn) == 1,
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
