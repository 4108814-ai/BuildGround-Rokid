package com.anezium.rokidbus.plugin.assistant

import android.content.BroadcastReceiver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderAlarmContract.ACTION_FIRE) {
            val id = intent.getStringExtra(ReminderAlarmContract.EXTRA_REMINDER_ID)
                ?.takeIf(String::isNotBlank)
                ?: return
            ReminderDeliveryService.start(context, id, late = false)
            return
        }
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val reminders = AssistantReminderStore(context).pending()
                val scheduler = androidReminderScheduler(context)
                val afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
                var scheduled = 0
                var deliveredLate = 0
                reminders.forEach { reminder ->
                    runCatching {
                        scheduler.schedule(
                            reminder = reminder,
                            afterBoot = afterBoot,
                            lateIfImmediate = true,
                        )
                    }.onSuccess { outcome ->
                        if (outcome.deliveredImmediately) deliveredLate += 1 else scheduled += 1
                    }.onFailure { error ->
                        Log.w(
                            TAG,
                            "reminder reschedule failed id=${reminder.id} " +
                                "error=${error.javaClass.simpleName}",
                        )
                    }
                }
                Log.i(
                    TAG,
                    "reminder restore count=${reminders.size} scheduled=$scheduled late=$deliveredLate",
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NexusAssistant"
    }
}

class ReminderDeliveryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startDeliveryForeground()
        val reminderId = intent?.getStringExtra(ReminderAlarmContract.EXTRA_REMINDER_ID)
            ?.takeIf(String::isNotBlank)
        if (reminderId == null) {
            finishStart(startId)
            return START_NOT_STICKY
        }
        val late = intent?.getBooleanExtra(ReminderAlarmContract.EXTRA_LATE, false) ?: false
        serviceScope.launch {
            try {
                val reminder = withContext(Dispatchers.IO) {
                    AssistantReminderStore(applicationContext).takeForDelivery(reminderId)
                } ?: return@launch
                postReminderNotification(reminder, late)
                runCatching {
                    OneShotReminderGlassesDelivery(applicationContext).deliver(reminder, late)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "glasses reminder delivery failed id=${reminder.id} " +
                            "error=${error.javaClass.simpleName}",
                    )
                }
                Log.i(TAG, "reminder delivered id=${reminder.id} late=$late")
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "reminder delivery failed id=$reminderId error=${error.javaClass.simpleName}",
                )
            } finally {
                finishStart(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDeliveryForeground() {
        val notification = Notification.Builder(this, DELIVERY_CHANNEL_ID)
            .setSmallIcon(com.anezium.rokidbus.plugin.assistant.R.drawable.nexus_glyph_assistant)
            .setContentTitle("Assistant reminder")
            .setContentText("Delivering reminder")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                DELIVERY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(DELIVERY_NOTIFICATION_ID, notification)
        }
    }

    private fun postReminderNotification(reminder: AssistantReminder, late: Boolean) {
        if (!ReminderPermissions.notificationsGranted(this)) return
        val kind = if (reminder.kind == AssistantReminderKind.TIMER) "Timer" else "Reminder"
        val title = if (late) "$kind (late)" else kind
        val notification = Notification.Builder(this, REMINDERS_CHANNEL_ID)
            .setSmallIcon(com.anezium.rokidbus.plugin.assistant.R.drawable.nexus_glyph_assistant)
            .setContentTitle(title)
            .setContentText(reminder.label)
            .setStyle(Notification.BigTextStyle().bigText(reminder.label))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        notificationManager.notify(reminder.id.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                REMINDERS_CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Assistant reminders and timers"
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DELIVERY_CHANNEL_ID,
                "Reminder delivery",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Brief background delivery of Assistant reminders"
                setShowBadge(false)
            },
        )
    }

    private fun finishStart(startId: Int) {
        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val TAG = "NexusAssistant"
        private const val REMINDERS_CHANNEL_ID = "assistant_reminders"
        private const val DELIVERY_CHANNEL_ID = "assistant_reminder_delivery"
        private const val DELIVERY_NOTIFICATION_ID = 0x4152

        fun start(context: Context, reminderId: String, late: Boolean) {
            val intent = Intent(context, ReminderDeliveryService::class.java)
                .putExtra(ReminderAlarmContract.EXTRA_REMINDER_ID, reminderId)
                .putExtra(ReminderAlarmContract.EXTRA_LATE, late)
            context.startForegroundService(intent)
        }
    }
}

private class OneShotReminderGlassesDelivery(
    private val context: Context,
) : NexusPluginCallbacks {
    private val completed = CompletableDeferred<Boolean>()
    private var approved = false
    private var linkReceived = false
    private var linkState = 0
    private var client: NexusPluginClient? = null
    private var reminder: AssistantReminder? = null
    private var late = false

    suspend fun deliver(reminder: AssistantReminder, late: Boolean): Boolean {
        this.reminder = reminder
        this.late = late
        var createdClient: NexusPluginClient? = null
        return try {
            withTimeoutOrNull(GLASSES_DELIVERY_TIMEOUT_MS) {
                NexusPluginClient.create(context, PLUGIN_ID, this@OneShotReminderGlassesDelivery)
                    .also { client ->
                        createdClient = client
                        this@OneShotReminderGlassesDelivery.client = client
                        client.connect()
                    }
                completed.await()
            } ?: false
        } finally {
            createdClient?.close()
            client = null
        }
    }

    override fun onOpen() = Unit
    override fun onClose() = Unit
    override fun onInput(event: NexusInputEvent) = Unit
    override fun onMessage(path: String, id: String, payload: JSONObject) = Unit

    override fun onRegistrationState(result: Int) {
        approved = result == PluginRegistrationResult.APPROVED
        if (
            result != PluginRegistrationResult.PENDING_USER_APPROVAL &&
            result != PluginRegistrationResult.APPROVED
        ) {
            completed.complete(false)
            return
        }
        tryDeliver()
    }

    override fun onLinkState(state: Int) {
        linkState = state
        linkReceived = true
        tryDeliver()
    }

    private fun tryDeliver() {
        if (!approved || !linkReceived || completed.isCompleted) return
        val value = reminder ?: return
        val currentClient = client ?: return
        val title = when (value.kind) {
            AssistantReminderKind.REMINDER -> if (late) "Reminder (late)" else "Reminder"
            AssistantReminderKind.TIMER -> if (late) "Timer (late)" else "Timer"
        }
        val result = runCatching {
            if (
                linkState and LinkStateBits.SPP_DATA_UP != 0 &&
                currentClient.supportsNoticeSurface
            ) {
                currentClient.showNotice(
                    NexusNotice(
                        title = title,
                        body = value.label,
                        // A reminder arrives unannounced, so it has to outlast the
                        // moment the wearer looks up. The default band lifetime is
                        // tuned for answers the wearer is already waiting for.
                        ttlMs = NOTICE_TTL_MS,
                        wakeDisplay = true,
                    ),
                )
            } else if (currentClient.supportsPinSurface) {
                currentClient.showPin(
                    NexusPin(
                        title = title.take(PIN_TITLE_CHARS),
                        lines = listOf(value.label.truncateAtWordBoundary(PIN_LINE_CHARS)),
                    ),
                )
            } else {
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            }
        }.getOrDefault(NexusSdkResult.CAPABILITY_NOT_AVAILABLE)
        completed.complete(result == NexusSdkResult.SENT)
    }

    private companion object {
        const val PLUGIN_ID = "assistant"
        const val GLASSES_DELIVERY_TIMEOUT_MS = 10_000L
        const val NOTICE_TTL_MS = 20_000L
        const val PIN_TITLE_CHARS = 24
        const val PIN_LINE_CHARS = 28
    }
}
