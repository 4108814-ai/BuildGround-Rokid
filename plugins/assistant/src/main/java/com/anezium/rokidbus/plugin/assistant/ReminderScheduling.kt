package com.anezium.rokidbus.plugin.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock

object ReminderPermissions {
    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

internal enum class AssistantAlarmClock {
    RTC,
    ELAPSED_REALTIME,
}

internal sealed interface AssistantAlarmPlan {
    data class Schedule(
        val id: String,
        val clock: AssistantAlarmClock,
        val triggerAtMillis: Long,
    ) : AssistantAlarmPlan

    data class DeliverImmediately(
        val id: String,
    ) : AssistantAlarmPlan
}

internal object ReminderAlarmPlanner {
    fun plan(
        reminder: AssistantReminder,
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
        afterBoot: Boolean = false,
    ): AssistantAlarmPlan = when (reminder.kind) {
        AssistantReminderKind.REMINDER -> if (reminder.epochMillis <= nowEpochMillis) {
            AssistantAlarmPlan.DeliverImmediately(reminder.id)
        } else {
            AssistantAlarmPlan.Schedule(
                id = reminder.id,
                clock = AssistantAlarmClock.RTC,
                triggerAtMillis = reminder.epochMillis,
            )
        }

        AssistantReminderKind.TIMER -> {
            val deadline = if (afterBoot) {
                val remaining = reminder.epochMillis - nowEpochMillis
                if (remaining <= 0L) return AssistantAlarmPlan.DeliverImmediately(reminder.id)
                nowElapsedRealtimeMillis + remaining
            } else {
                reminder.elapsedRealtimeDeadlineMs
                    ?: nowElapsedRealtimeMillis + (reminder.epochMillis - nowEpochMillis)
            }
            if (deadline <= nowElapsedRealtimeMillis) {
                AssistantAlarmPlan.DeliverImmediately(reminder.id)
            } else {
                AssistantAlarmPlan.Schedule(
                    id = reminder.id,
                    clock = AssistantAlarmClock.ELAPSED_REALTIME,
                    triggerAtMillis = deadline,
                )
            }
        }
    }

    fun plansAfterRestart(
        reminders: List<AssistantReminder>,
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
        afterBoot: Boolean,
    ): List<AssistantAlarmPlan> = reminders.map { reminder ->
        plan(
            reminder = reminder,
            nowEpochMillis = nowEpochMillis,
            nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
            afterBoot = afterBoot,
        )
    }
}

internal data class ReminderScheduleOutcome(
    val exact: Boolean,
    val deliveredImmediately: Boolean,
)

internal interface ReminderAlarmGateway {
    /** Returns whether an exact alarm was actually used. */
    fun schedule(
        id: String,
        clock: AssistantAlarmClock,
        triggerAtMillis: Long,
        exactRequested: Boolean,
    ): Boolean

    fun cancel(id: String)
    fun deliverNow(id: String, late: Boolean)
}

internal interface AssistantReminderScheduler {
    fun schedule(
        reminder: AssistantReminder,
        afterBoot: Boolean = false,
        lateIfImmediate: Boolean = false,
    ): ReminderScheduleOutcome

    fun cancel(id: String)
}

internal class DefaultAssistantReminderScheduler(
    private val gateway: ReminderAlarmGateway,
    private val canScheduleExact: () -> Boolean,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) : AssistantReminderScheduler {
    override fun schedule(
        reminder: AssistantReminder,
        afterBoot: Boolean,
        lateIfImmediate: Boolean,
    ): ReminderScheduleOutcome {
        return when (
            val plan = ReminderAlarmPlanner.plan(
                reminder = reminder,
                nowEpochMillis = epochClock(),
                nowElapsedRealtimeMillis = elapsedClock(),
                afterBoot = afterBoot,
            )
        ) {
            is AssistantAlarmPlan.DeliverImmediately -> {
                gateway.deliverNow(plan.id, lateIfImmediate)
                ReminderScheduleOutcome(exact = true, deliveredImmediately = true)
            }
            is AssistantAlarmPlan.Schedule -> ReminderScheduleOutcome(
                exact = gateway.schedule(
                    id = plan.id,
                    clock = plan.clock,
                    triggerAtMillis = plan.triggerAtMillis,
                    exactRequested = canScheduleExact(),
                ),
                deliveredImmediately = false,
            )
        }
    }

    override fun cancel(id: String) {
        gateway.cancel(id)
    }
}

internal fun androidReminderScheduler(context: Context): AssistantReminderScheduler {
    val appContext = context.applicationContext
    return DefaultAssistantReminderScheduler(
        gateway = AndroidReminderAlarmGateway(appContext),
        canScheduleExact = { ReminderPermissions.canScheduleExact(appContext) },
    )
}

private class AndroidReminderAlarmGateway(
    private val context: Context,
) : ReminderAlarmGateway {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(
        id: String,
        clock: AssistantAlarmClock,
        triggerAtMillis: Long,
        exactRequested: Boolean,
    ): Boolean {
        val type = when (clock) {
            AssistantAlarmClock.RTC -> AlarmManager.RTC_WAKEUP
            AssistantAlarmClock.ELAPSED_REALTIME -> AlarmManager.ELAPSED_REALTIME_WAKEUP
        }
        val operation = alarmPendingIntent(id)
        if (exactRequested) {
            try {
                alarmManager.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
                return true
            } catch (_: SecurityException) {
                // The special access may have been revoked between the capability check and call.
            }
        }
        alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, operation)
        return false
    }

    override fun cancel(id: String) {
        alarmManager.cancel(alarmPendingIntent(id))
    }

    override fun deliverNow(id: String, late: Boolean) {
        ReminderDeliveryService.start(context, id, late)
    }

    private fun alarmPendingIntent(id: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ReminderAlarmContract.ACTION_FIRE)
            .setData(
                Uri.Builder()
                    .scheme("nexus-assistant")
                    .authority("reminder")
                    .appendPath(id)
                    .build(),
            )
            .putExtra(ReminderAlarmContract.EXTRA_REMINDER_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal object ReminderAlarmContract {
    const val ACTION_FIRE = "com.anezium.rokidbus.plugin.assistant.action.REMINDER_FIRE"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_LATE = "late"
}
