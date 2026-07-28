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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the connections to the agent providers while the HUD is closed.
 *
 * This service says nothing. It posts no alert, no summary, no progress: the
 * only notification it builds is the one `startForeground` requires, and the app
 * holds no notification permission, so Android never shows it. Everything the
 * wearer needs to know reaches them on the glasses, raised by
 * [AgentsPluginService].
 */
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
    private val linkServer by lazy {
        AgentdLinkServer(AgentsRuntime.store, configStore, serviceScope) { machineName ->
            AgentsRuntime.announceLinkedMachine(machineName)
        }
    }
    private var runningAgentd: AgentdConfig? = null
    private var runningOpenClaw: OpenClawConfig? = null
    private var temporaryAgentd: Job? = null
    private var temporaryOpenClaw: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        startForeground(MONITOR_NOTIFICATION_ID, notifications.monitorNotification())
        serviceScope.launch {
            while (true) {
                delay(PRUNE_INTERVAL_MS)
                configStore.forgetNotificationFingerprints(AgentsRuntime.store.prune())
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
                intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
                    agentdClient.openDetail(sessionId)
                    linkServer.openDetail(sessionId)
                }
            }
            ACTION_CLOSE_DETAIL -> {
                agentdClient.closeDetail()
                linkServer.closeDetail()
            }
            ACTION_DECIDE_APPROVAL -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                val decision = ApprovalDecision.values()
                    .firstOrNull { it.wireValue == intent.getStringExtra(EXTRA_DECISION) }
                if (requestId != null && decision != null) {
                    agentdClient.decideApproval(requestId, decision)
                    linkServer.decideApproval(requestId, decision)
                    // The wearer answered: the question is gone from the board
                    // whether or not the daemon's acknowledgement makes it back.
                    AgentsRuntime.store.resolveApproval(requestId)
                }
            }
            else -> reconcile()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        temporaryAgentd?.cancel()
        temporaryOpenClaw?.cancel()
        agentdClient.stop(clearSessions = true)
        linkServer.stop(clearSessions = true)
        openClawClient.stop(clearSessions = true)
        releasePluginService()
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
        // Pairing data is the away-from-home path (we dial the daemon over the
        // tailnet). With none configured we simply listen: the daemon finds us.
        val wantedAgentd = config.agentd?.takeIf { config.agentdEnabled && it.configured }
        // A test started while the provider was off installs a timer that stops
        // it again. Enabling the provider in the meantime must disarm that timer,
        // or it fires later and kills a connection nobody asked it to touch.
        if (config.agentdEnabled) {
            temporaryAgentd?.cancel()
            temporaryAgentd = null
        }
        if (wantedAgentd != runningAgentd) {
            if (wantedAgentd == null) {
                agentdClient.stop(clearSessions = true)
            } else {
                agentdClient.start(wantedAgentd)
            }
            runningAgentd = wantedAgentd
        }
        if (config.agentdEnabled && wantedAgentd == null) {
            linkServer.start()
        } else {
            linkServer.stop(clearSessions = wantedAgentd == null)
        }
        if (config.openClawEnabled) {
            temporaryOpenClaw?.cancel()
            temporaryOpenClaw = null
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
        temporaryAgentd = null
        if (!config.agentdEnabled) {
            temporaryAgentd = serviceScope.launch {
                delay(TEST_WINDOW_MS)
                // Enabled during the window: the permanent client owns it now.
                if (configStore.load().agentdEnabled) return@launch
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
        temporaryOpenClaw = null
        if (!config.openClawEnabled) {
            temporaryOpenClaw = serviceScope.launch {
                delay(TEST_WINDOW_MS)
                if (configStore.load().openClawEnabled) return@launch
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
        linkServer.stop(clearSessions = true)
        openClawClient.stop(clearSessions = true)
        runningAgentd = null
        runningOpenClaw = null
        releasePluginService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The plugin service is the hub's to own while a surface is up. We only
     * started it to keep a bus client alive for alerts, so we only take that
     * back when the wearer is not actually looking at it.
     */
    private fun releasePluginService() {
        if (AgentsRuntime.hudOpen) return
        stopService(Intent(this, AgentsPluginService::class.java))
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
        const val ACTION_DECIDE_APPROVAL =
            "com.anezium.rokidbus.plugin.agents.action.DECIDE_APPROVAL"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_DECISION = "decision"
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
                if (!AgentsRuntime.hudOpen) {
                    context.stopService(Intent(context, AgentsPluginService::class.java))
                }
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

        /** The wearer answered a held tool call on the glasses. */
        fun decideApproval(context: Context, requestId: String, decision: ApprovalDecision) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentsMonitorService::class.java)
                    .setAction(ACTION_DECIDE_APPROVAL)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_DECISION, decision.wireValue),
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
