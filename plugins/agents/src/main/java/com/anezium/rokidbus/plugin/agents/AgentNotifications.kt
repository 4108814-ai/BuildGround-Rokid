package com.anezium.rokidbus.plugin.agents

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The one notification this plugin is allowed to build.
 *
 * Agents says nothing on the phone. Everything an agent needs from the wearer
 * reaches them on the glasses, where they are already looking. The only object
 * left here is the notification `startForeground` demands in exchange for a
 * background connection — and since the app does not hold POST_NOTIFICATIONS,
 * Android never shows it in the shade. It exists to satisfy an API, not to be
 * read: the wearer sees the service only where Android insists, in the system's
 * own running-apps panel.
 */
class AgentNotifications(private val context: Context) {
    fun createChannels() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Agent monitor",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            },
        )
    }

    fun monitorNotification(): android.app.Notification =
        NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agents)
            .setContentTitle("Nexus Agents")
            .setContentText("Watching your agent sessions")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(settingsPendingIntent())
            .build()

    private fun settingsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, AgentsSettingsActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val MONITOR_CHANNEL_ID = "nexus_agent_monitor"
    }
}
