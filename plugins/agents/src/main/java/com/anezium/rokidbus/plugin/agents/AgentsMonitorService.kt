package com.anezium.rokidbus.plugin.agents

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.plugin.agents.BuildConfig.VERSION_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentsMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val notifications by lazy { AgentNotifications(applicationContext) }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
    private val agentdClient by lazy {
        AgentdClient(httpClient, AgentsRuntime.store, serviceScope, VERSION_NAME)
    }
    private val openClawClient by lazy {
        OpenClawClient(httpClient, AgentsRuntime.store, configStore, serviceScope, VERSION_NAME)
    }
    private var runningAgentd: AgentdConfig? = null
    private var runningOpenClaw: OpenClawConfig? = null
    private var temporaryAgentd: Job? = null
    private var temporaryOpenClaw: Job? = null
    private var previousSessions: List<AgentSession> = emptyList()
    private val decisionEngine by lazy {
        NotificationDecisionEngine(
            readFingerprint = configStore::notificationFingerprint,
            writeFingerprint = configStore::saveNotificationFingerprint,
        )
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        startForeground(
            MONITOR_NOTIFICATION_ID,
            notifications.monitorNotification("Starting connections"),
        )
        observeRuntime()
        serviceScope.launch {
            while (true) {
                delay(PRUNE_INTERVAL_MS)
                AgentsRuntime.store.prune()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_AGENTD -> {
                if (configStore.load().shouldMonitor) reconcile()
                testAgentd()
            }
            ACTION_TEST_OPENCLAW -> {
                if (configStore.load().shouldMonitor) reconcile()
                testOpenClaw()
            }
            ACTION_OPEN_DETAIL -> {
                intent.getStringExtra(EXTRA_SESSION_ID)?.let(agentdClient::openDetail)
            }
            ACTION_CLOSE_DETAIL -> agentdClient.closeDetail()
            else -> reconcile()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        temporaryAgentd?.cancel()
        temporaryOpenClaw?.cancel()
        agentdClient.stop(clearSessions = true)
        openClawClient.stop(clearSessions = true)
        stopService(Intent(this, AgentsPluginService::class.java))
        httpClient.dispatcher.executorService.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun reconcile() {
        val config = configStore.load()
        if (!config.shouldMonitor) {
            stopMonitoring()
            return
        }
        startService(
            Intent(this, AgentsPluginService::class.java)
                .setAction(AgentsPluginService.ACTION_MONITOR_ACTIVE),
        )
        val wantedAgentd = config.agentd?.takeIf { config.agentdEnabled && it.configured }
        if (wantedAgentd != runningAgentd) {
            if (wantedAgentd == null) {
                agentdClient.stop(clearSessions = true)
            } else {
                agentdClient.start(wantedAgentd)
            }
            runningAgentd = wantedAgentd
        }
        val wantedOpenClaw = config.openClaw?.takeIf { config.openClawEnabled && it.configured }
        if (wantedOpenClaw != runningOpenClaw) {
            if (wantedOpenClaw == null) {
                openClawClient.stop(clearSessions = true)
            } else {
                openClawClient.start(wantedOpenClaw)
            }
            runningOpenClaw = wantedOpenClaw
        }
    }

    private fun testAgentd() {
        val config = configStore.load()
        val pairing = config.agentd ?: return stopIfNoConfiguredProvider()
        agentdClient.start(pairing)
        runningAgentd = if (config.agentdEnabled) pairing else null
        temporaryAgentd?.cancel()
        if (!config.agentdEnabled) {
            temporaryAgentd = serviceScope.launch {
                delay(TEST_WINDOW_MS)
                agentdClient.stop(clearSessions = true)
                temporaryAgentd = null
                stopIfNoConfiguredProvider()
            }
        }
    }

    private fun testOpenClaw() {
        val config = configStore.load()
        val gateway = config.openClaw ?: return stopIfNoConfiguredProvider()
        openClawClient.start(gateway)
        runningOpenClaw = if (config.openClawEnabled) gateway else null
        temporaryOpenClaw?.cancel()
        if (!config.openClawEnabled) {
            temporaryOpenClaw = serviceScope.launch {
                delay(TEST_WINDOW_MS)
                openClawClient.stop(clearSessions = true)
                temporaryOpenClaw = null
                stopIfNoConfiguredProvider()
            }
        }
    }

    private fun stopIfNoConfiguredProvider() {
        if (!configStore.load().shouldMonitor &&
            temporaryAgentd?.isActive != true &&
            temporaryOpenClaw?.isActive != true
        ) {
            stopMonitoring()
        }
    }

    private fun stopMonitoring() {
        agentdClient.stop(clearSessions = true)
        openClawClient.stop(clearSessions = true)
        runningAgentd = null
        runningOpenClaw = null
        stopService(Intent(this, AgentsPluginService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeRuntime() {
        serviceScope.launch {
            AgentsRuntime.store.sessions.collectLatest { current ->
                val enabled = configStore.load()
                val visiblePrevious = previousSessions.filter { session ->
                    session.provider == AgentProvider.CLAUDE && enabled.agentdEnabled ||
                        session.provider == AgentProvider.OPENCLAW && enabled.openClawEnabled
                }
                val visibleCurrent = current.filter { session ->
                    session.provider == AgentProvider.CLAUDE && enabled.agentdEnabled ||
                        session.provider == AgentProvider.OPENCLAW && enabled.openClawEnabled
                }
                decisionEngine.transitions(visiblePrevious, visibleCurrent).forEach { decision ->
                    notifications.notifySession(decision)
                    if (decision.session.status == AgentStatus.NEEDS_YOU) {
                        startService(
                            Intent(this@AgentsMonitorService, AgentsPluginService::class.java)
                                .setAction(AgentsPluginService.ACTION_ATTENTION),
                        )
                    }
                }
                previousSessions = current
            }
        }
        serviceScope.launch {
            combine(
                AgentsRuntime.store.connections,
                AgentsRuntime.store.sessions,
            ) { connections, sessions ->
                val cc = connections.getValue(AgentProvider.CLAUDE).state.shortLabel()
                val oc = connections.getValue(AgentProvider.OPENCLAW).state.shortLabel()
                "$cc CC · $oc OC · ${sessions.size} sessions"
            }.collectLatest { summary ->
                getSystemService(android.app.NotificationManager::class.java).notify(
                    MONITOR_NOTIFICATION_ID,
                    notifications.monitorNotification(summary),
                )
            }
        }
    }

    companion object {
        const val ACTION_RECONCILE =
            "com.anezium.rokidbus.plugin.agents.action.RECONCILE"
        const val ACTION_TEST_AGENTD =
            "com.anezium.rokidbus.plugin.agents.action.TEST_AGENTD"
        const val ACTION_TEST_OPENCLAW =
            "com.anezium.rokidbus.plugin.agents.action.TEST_OPENCLAW"
        const val ACTION_OPEN_DETAIL =
            "com.anezium.rokidbus.plugin.agents.action.OPEN_DETAIL"
        const val ACTION_CLOSE_DETAIL =
            "com.anezium.rokidbus.plugin.agents.action.CLOSE_DETAIL"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val MONITOR_NOTIFICATION_ID = 3101
        private const val TEST_WINDOW_MS = 15_000L
        private const val PRUNE_INTERVAL_MS = 60_000L

        fun reconcile(context: Context) {
            val config = AgentsConfigStore(context).load()
            val intent = Intent(context, AgentsMonitorService::class.java)
                .setAction(ACTION_RECONCILE)
            if (config.shouldMonitor) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
                context.stopService(Intent(context, AgentsPluginService::class.java))
            }
        }

        /** The wearer opened a conversation on the HUD: ask the daemon for it. */
        fun openDetail(context: Context, session: AgentSession) {
            if (session.provider != AgentProvider.CLAUDE) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentsMonitorService::class.java)
                    .setAction(ACTION_OPEN_DETAIL)
                    .putExtra(EXTRA_SESSION_ID, session.id),
            )
        }

        fun closeDetail(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentsMonitorService::class.java)
                    .setAction(ACTION_CLOSE_DETAIL),
            )
        }

        fun test(context: Context, provider: AgentProvider) {
            val action = when (provider) {
                AgentProvider.CLAUDE -> ACTION_TEST_AGENTD
                AgentProvider.OPENCLAW -> ACTION_TEST_OPENCLAW
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentsMonitorService::class.java).setAction(action),
            )
        }
    }
}

private fun ConnectionState.shortLabel(): String = when (this) {
    ConnectionState.CONNECTED -> "ON"
    ConnectionState.CONNECTING -> "WAIT"
    ConnectionState.AUTH_FAILED -> "AUTH"
    ConnectionState.DISCONNECTED -> "OFF"
}
