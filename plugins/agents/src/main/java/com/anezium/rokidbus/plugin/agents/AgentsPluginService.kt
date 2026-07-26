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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

class AgentsPluginService : NexusPluginService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var surface: NexusSurfaceSession? = null
    private var surfaceShown = false
    private var selectedIndex = 0
    private var ageTicker: Job? = null

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
        render(show = true)
        ageTicker?.cancel()
        ageTicker = serviceScope.launch {
            while (isActive) {
                delay(AGE_TICK_MS)
                if (surfaceShown) render(show = false)
            }
        }
        if (AgentsConfigStore(applicationContext).load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onNexusClose() {
        ageTicker?.cancel()
        ageTicker = null
        surfaceShown = false
        surface?.hide()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val count = AgentsRuntime.store.sessions.value.size
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
        val now = System.currentTimeMillis()
        val sessions = AgentsRuntime.store.sessions.value
        val connections = AgentsRuntime.store.connections.value
        val config = AgentsConfigStore(applicationContext).load()
        selectedIndex = selectedIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))

        val alerts = buildList {
            if (config.agentdEnabled &&
                connections[AgentProvider.CLAUDE]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(alertLine("Claude pairing rejected — re-pair in the phone app"))
            }
            if (config.openClawEnabled &&
                connections[AgentProvider.OPENCLAW]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(alertLine("OpenClaw auth failed — check token in the phone app"))
            }
        }

        // The HUD board does not scroll: show a window of rows sliding with the selection.
        val windowSize = (VISIBLE_SESSION_ROWS - alerts.size).coerceAtLeast(MIN_SESSION_ROWS)
        val windowStart = (selectedIndex - windowSize / 2)
            .coerceIn(0, (sessions.size - windowSize).coerceAtLeast(0))
        val visible = sessions.drop(windowStart).take(windowSize)

        val richLines = buildList {
            addAll(alerts)
            if (sessions.isEmpty()) {
                add(emptyLine(config))
            } else {
                visible.forEachIndexed { offset, session ->
                    add(sessionLine(session, windowStart + offset == selectedIndex, now))
                }
            }
        }

        val askCount = sessions.count { it.status == AgentStatus.NEEDS_YOU }
        val runCount = sessions.count { it.status == AgentStatus.WORKING }
        val title = when {
            askCount > 0 -> "Agents · $askCount need you"
            runCount > 0 -> "Agents · $runCount running"
            else -> "Agents"
        }
        val footer = buildFooter(config, connections, sessions.size)

        val keySource = buildString {
            append(title).append('\n').append(footer).append('\n')
            richLines.forEach { line ->
                append(line.badge).append(':').append(line.text).append(':')
                append(line.trail.joinToString(",")).append('\n')
            }
        }
        val contentKey = MessageDigest.getInstance("SHA-256")
            .digest(keySource.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return NexusCard(
            title = title,
            lines = emptyList(),
            footer = footer,
            contentKey = contentKey,
            richLines = richLines,
            handlesBack = true,
        )
    }

    private fun sessionLine(session: AgentSession, selected: Boolean, now: Long): NexusCardLine {
        val summary = if (session.status == AgentStatus.NEEDS_YOU) pendingSummary(session) else null
        val titleBudget = if (summary == null) 200 else 100
        val text = buildString {
            append(session.displayTitle.singleLine(titleBudget))
            if (summary != null) append(" — ").append(summary.singleLine(110))
        }.take(238)
        return NexusCardLine(
            text = text,
            // The badge chip is the only fixed-position element on the row, so it
            // carries the selection marker; the text itself may marquee.
            badge = if (selected) "›${session.provider.marker}" else session.provider.marker,
            trail = listOfNotNull(session.status.boardToken(), age(now, session.lastActivityAt)),
        )
    }

    /** Rewrites the two stock Claude notification messages into short board phrases. */
    private fun pendingSummary(session: AgentSession): String? {
        val raw = session.pendingRequest?.summary?.singleLine(160) ?: return null
        PERMISSION_PATTERN.find(raw)?.let { return "asks: ${it.groupValues[1]}" }
        if (raw.contains("waiting for your input", ignoreCase = true)) return "waiting for input"
        return raw
    }

    private fun alertLine(text: String): NexusCardLine =
        NexusCardLine(text = text.take(238), badge = "!", trail = listOf("ERR"))

    private fun emptyLine(config: AgentsConfig): NexusCardLine {
        val text = if (config.agentdEnabled || config.openClawEnabled) {
            "No agent sessions yet"
        } else {
            "Set up providers in the phone app"
        }
        return NexusCardLine(text = text, badge = "·")
    }

    private fun buildFooter(
        config: AgentsConfig,
        connections: Map<AgentProvider, ProviderConnectionState>,
        total: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (config.agentdEnabled) {
            parts.add("CC ${connections[AgentProvider.CLAUDE]?.state.footerLabel()}")
        }
        if (config.openClawEnabled) {
            parts.add("OC ${connections[AgentProvider.OPENCLAW]?.state.footerLabel()}")
        }
        if (parts.isEmpty()) parts.add("no providers enabled")
        if (total > 0) parts.add("${selectedIndex + 1}/$total")
        return parts.joinToString(" · ").take(238)
    }

    private fun age(now: Long, at: Long?): String? {
        if (at == null) return null
        val elapsed = (now - at).coerceAtLeast(0)
        return when {
            elapsed < 60_000L -> "now"
            elapsed < 3_600_000L -> "${elapsed / 60_000L}m"
            elapsed < 86_400_000L -> "${elapsed / 3_600_000L}h"
            else -> "${elapsed / 86_400_000L}d"
        }
    }

    companion object {
        const val ACTION_MONITOR_ACTIVE =
            "com.anezium.rokidbus.plugin.agents.action.MONITOR_ACTIVE"
        const val ACTION_ATTENTION =
            "com.anezium.rokidbus.plugin.agents.action.ATTENTION"
        private const val SURFACE_ID = "agents"
        private const val VISIBLE_SESSION_ROWS = 6
        private const val MIN_SESSION_ROWS = 4
        private const val AGE_TICK_MS = 60_000L
        private val PERMISSION_PATTERN =
            Regex("permission to use (.+)", RegexOption.IGNORE_CASE)
    }
}

private fun AgentStatus.boardToken(): String = when (this) {
    AgentStatus.NEEDS_YOU -> "ASK"
    AgentStatus.WORKING -> "RUN"
    AgentStatus.IDLE -> "IDLE"
    AgentStatus.ERROR -> "ERR"
    AgentStatus.DONE -> "DONE"
}

private fun ConnectionState?.footerLabel(): String = when (this) {
    ConnectionState.CONNECTED -> "on"
    ConnectionState.CONNECTING -> "…"
    ConnectionState.AUTH_FAILED -> "auth!"
    ConnectionState.DISCONNECTED, null -> "off"
}
