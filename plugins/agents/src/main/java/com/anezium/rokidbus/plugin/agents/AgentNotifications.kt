package com.anezium.rokidbus.plugin.agents

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AgentNotifications(private val context: Context) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "Agent sessions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Agent monitor",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun notifySession(decision: AgentNotificationDecision) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val session = decision.session
        val summary = when (session.status) {
            AgentStatus.NEEDS_YOU -> session.pendingRequest?.summary
            AgentStatus.ERROR -> session.statusDetail
            else -> null
        }?.singleLine(180)
        val text = buildString {
            append(session.status.wireValue.replace('_', ' '))
            if (!summary.isNullOrBlank()) append(" · ").append(summary)
        }
        val notification = NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agents)
            .setContentTitle(session.displayTitle.singleLine(120))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(settingsPendingIntent())
            .build()
        NotificationManagerCompat.from(context).notify(session.key.hashCode(), notification)
    }

    /** First contact from a computer: tell the wearer who just linked up. */
    fun notifyMachineTrusted(machineName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agents)
            .setContentTitle("$machineName is now linked")
            .setContentText("Its Claude Code sessions appear on your glasses")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(settingsPendingIntent())
            .build()
        NotificationManagerCompat.from(context).notify(machineName.hashCode(), notification)
    }

    fun monitorNotification(summary: String) =
        NotificationCompat.Builder(context, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agents)
            .setContentTitle("Nexus Agents")
            .setContentText(summary)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(settingsPendingIntent())
            .build()

    private fun settingsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, AgentsSettingsActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val SESSION_CHANNEL_ID = "nexus_agent_sessions"
        const val MONITOR_CHANNEL_ID = "nexus_agent_monitor"
    }
}
