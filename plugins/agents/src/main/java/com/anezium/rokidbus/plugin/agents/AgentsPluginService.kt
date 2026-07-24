package com.anezium.rokidbus.plugin.agents

import android.content.Intent
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.security.MessageDigest

class AgentsPluginService : NexusPluginService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var surface: NexusSurfaceSession? = null
    private var surfaceShown = false
    private var selectedIndex = 0

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            combine(
                AgentsRuntime.store.sessions,
                AgentsRuntime.store.connections,
            ) { sessions, connections -> sessions to connections }
                .collectLatest {
                    if (surfaceShown) render(show = false)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ATTENTION && !surfaceShown) {
            mainExecutor.execute {
                if (!surfaceShown &&
                    AgentsRuntime.store.sessions.value.any {
                        it.status == AgentStatus.NEEDS_YOU
                    }
                ) {
                    attemptAdoption()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        surfaceShown = true
        selectedIndex = selectedIndex.coerceIn(
            0,
            (AgentsRuntime.store.sessions.value.size - 1).coerceAtLeast(0),
        )
        render(show = true)
        if (AgentsConfigStore(applicationContext).load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onNexusClose() {
        surfaceShown = false
        surface?.hide()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val count = AgentsRuntime.store.sessions.value.take(MAX_SESSION_ROWS).size
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (count > 0) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                render(show = false)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (count > 0) selectedIndex = (selectedIndex + 1).coerceAtMost(count - 1)
                render(show = false)
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> Unit
            KeyEvent.KEYCODE_BACK -> surface?.hide()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        surfaceShown = false
        surface = null
        super.onDestroy()
    }

    private fun attemptAdoption() {
        val adoptionSurface = surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }
        adoptionSurface?.showCard(buildCard())
    }

    private fun render(show: Boolean) {
        val activeSurface = surface ?: return
        val card = buildCard()
        if (show) activeSurface.showCard(card) else activeSurface.updateCard(card)
    }

    private fun buildCard(): NexusCard {
        val allSessions = AgentsRuntime.store.sessions.value
        val sessions = allSessions.take(MAX_SESSION_ROWS)
        selectedIndex = selectedIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))
        val connections = AgentsRuntime.store.connections.value
        val richLines = buildList {
            add(headerLine(allSessions, connections))
            if (sessions.isEmpty()) {
                add(NexusCardLine(text = "No agent sessions", badge = "--"))
            } else {
                sessions.forEachIndexed { index, session ->
                    val prefix = if (index == selectedIndex) "› " else ""
                    val summary = if (session.status == AgentStatus.NEEDS_YOU) {
                        session.pendingRequest?.summary?.singleLine(100)
                    } else {
                        null
                    }
                    val titleBudget = if (summary.isNullOrBlank()) 220 else 110
                    val text = buildString {
                        append(prefix)
                        append(session.displayTitle.singleLine(titleBudget))
                        if (!summary.isNullOrBlank()) append(" — ").append(summary)
                    }.take(238)
                    add(
                        NexusCardLine(
                            text = text,
                            badge = session.provider.marker,
                            trail = listOf(session.status.wireValue.replace('_', ' ').take(24)),
                        ),
                    )
                }
            }
        }
        val keySource = richLines.joinToString("|") { line ->
            "${line.badge}:${line.text}:${line.trail.joinToString(",")}"
        }
        val contentKey = MessageDigest.getInstance("SHA-256")
            .digest(keySource.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return NexusCard(
            title = "Agents",
            lines = emptyList(),
            footer = "up/down select · back",
            contentKey = contentKey,
            richLines = richLines,
            handlesBack = true,
        )
    }

    private fun headerLine(
        sessions: List<AgentSession>,
        connections: Map<AgentProvider, ProviderConnectionState>,
    ): NexusCardLine {
        val counts = AgentStatus.values().associateWith { status ->
            sessions.count { it.status == status }
        }
        val cc = connections.getValue(AgentProvider.CLAUDE).state.boardLabel()
        val oc = connections.getValue(AgentProvider.OPENCLAW).state.boardLabel()
        return NexusCardLine(
            text = "CC $cc · OC $oc",
            badge = "STATUS",
            trail = listOf(
                "N:${counts.getValue(AgentStatus.NEEDS_YOU)}",
                "W:${counts.getValue(AgentStatus.WORKING)}",
                "I:${counts.getValue(AgentStatus.IDLE)}",
                "E:${counts.getValue(AgentStatus.ERROR)}",
                "D:${counts.getValue(AgentStatus.DONE)}",
            ),
        )
    }

    companion object {
        const val ACTION_MONITOR_ACTIVE =
            "com.anezium.rokidbus.plugin.agents.action.MONITOR_ACTIVE"
        const val ACTION_ATTENTION =
            "com.anezium.rokidbus.plugin.agents.action.ATTENTION"
        private const val SURFACE_ID = "agents"
        private const val MAX_SESSION_ROWS = 63
    }
}

private fun ConnectionState.boardLabel(): String = when (this) {
    ConnectionState.CONNECTED -> "ON"
    ConnectionState.CONNECTING -> "…"
    ConnectionState.AUTH_FAILED -> "AUTH"
    ConnectionState.DISCONNECTED -> "OFF"
}
