package com.anezium.rokidbus.plugin.agents

import android.content.Intent
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
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

    /** 0 keeps the conversation pinned to its newest message. */
    private var scrollBack = 0
    private var ageTicker: Job? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            combine(
                AgentsRuntime.store.sessions,
                AgentsRuntime.store.connections,
                AgentsRuntime.store.conversation,
            ) { _, _, _ -> Unit }
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
                    // Whatever the wearer was reading, the alert takes the surface.
                    leaveConversation()
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
        leaveConversation()
        surface?.hide()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val conversation = AgentsRuntime.store.conversation.value
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP ->
                if (conversation != null) scrollConversation(+1) else moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (conversation != null) scrollConversation(-1) else moveSelection(+1)
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> if (conversation == null) enterConversation()
            KeyEvent.KEYCODE_BACK ->
                if (conversation != null) {
                    leaveConversation()
                    render(show = false)
                } else {
                    surface?.hide()
                }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        surfaceShown = false
        surface = null
        super.onDestroy()
    }

    private fun moveSelection(delta: Int) {
        val count = AgentsRuntime.store.sessions.value.size
        if (count > 0) {
            selectedIndex = (selectedIndex + delta).coerceIn(0, count - 1)
        }
        render(show = false)
    }

    private fun scrollConversation(delta: Int) {
        val total = AgentsRuntime.store.conversation.value?.messages?.size ?: 0
        val maxScroll = (total - DETAIL_VISIBLE_ROWS).coerceAtLeast(0)
        scrollBack = (scrollBack + delta).coerceIn(0, maxScroll)
        render(show = false)
    }

    private fun enterConversation() {
        val session = AgentsRuntime.store.sessions.value.getOrNull(selectedIndex) ?: return
        scrollBack = 0
        AgentsRuntime.store.openConversation(session)
        AgentsMonitorService.openDetail(applicationContext, session)
        render(show = false)
    }

    private fun leaveConversation() {
        if (AgentsRuntime.store.conversation.value == null) return
        AgentsRuntime.store.closeConversation()
        AgentsMonitorService.closeDetail(applicationContext)
        scrollBack = 0
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

    private fun buildCard(): NexusCard =
        AgentsRuntime.store.conversation.value?.let(::conversationCard) ?: sessionsCard()

    // ---------------------------------------------------------------- sessions

    private fun sessionsCard(): NexusCard {
        val now = System.currentTimeMillis()
        val sessions = AgentsRuntime.store.sessions.value
        val connections = AgentsRuntime.store.connections.value
        val config = AgentsConfigStore(applicationContext).load()
        selectedIndex = selectedIndex.coerceIn(0, (sessions.size - 1).coerceAtLeast(0))

        val alerts = buildList {
            if (config.agentdEnabled &&
                connections[AgentProvider.CLAUDE]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(problemRow("Claude pairing rejected", "re-pair in the phone app"))
            }
            if (config.openClawEnabled &&
                connections[AgentProvider.OPENCLAW]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(problemRow("OpenClaw auth failed", "check the token in the phone app"))
            }
        }

        // The HUD does not scroll: keep a window around the selection.
        val windowSize = (VISIBLE_SESSION_ROWS - alerts.size).coerceAtLeast(MIN_SESSION_ROWS)
        val windowStart = (selectedIndex - windowSize / 2)
            .coerceIn(0, (sessions.size - windowSize).coerceAtLeast(0))

        val rows = buildList {
            addAll(alerts)
            if (sessions.isEmpty()) {
                add(emptyRow(config))
            } else {
                sessions.drop(windowStart).take(windowSize).forEachIndexed { offset, session ->
                    add(sessionRow(session, windowStart + offset == selectedIndex, now))
                }
            }
        }

        return card(
            title = "Agents",
            subtitle = sessionsSubtitle(sessions, connections, config),
            rows = rows,
            footer = if (sessions.isEmpty()) {
                "BACK exit"
            } else {
                "UP/DOWN move · ENTER open · BACK exit"
            },
        )
    }

    private fun sessionRow(session: AgentSession, selected: Boolean, now: Long): NexusCardLine {
        val age = age(now, session.lastActivityAt)
        val trail = when (session.status) {
            AgentStatus.NEEDS_YOU -> listOfNotNull("ASK", age)
            AgentStatus.WORKING -> listOfNotNull("RUN", age)
            AgentStatus.ERROR -> listOfNotNull("ERR", age)
            AgentStatus.DONE -> listOfNotNull("done", age)
            AgentStatus.IDLE -> listOfNotNull(age)
        }
        return NexusCardLine(
            text = session.displayTitle.singleLine(120),
            sub = sessionSub(session)?.singleLine(120),
            tone = when (session.status) {
                AgentStatus.NEEDS_YOU, AgentStatus.ERROR -> NexusRowTone.ALERT
                AgentStatus.WORKING -> NexusRowTone.NORMAL
                AgentStatus.IDLE, AgentStatus.DONE -> NexusRowTone.DIM
            },
            selected = selected,
            trail = trail,
        )
    }

    /** The line that says what this session is actually doing to you, or for you. */
    private fun sessionSub(session: AgentSession): String? = when (session.status) {
        AgentStatus.NEEDS_YOU -> pendingSummary(session)
        AgentStatus.ERROR -> session.statusDetail ?: "stopped with an error"
        AgentStatus.WORKING -> session.turn?.lastTool?.let { "running $it" }
            ?: session.lastAssistantText
        // Quiet sessions: where they live is the only thing worth a second line.
        AgentStatus.IDLE, AgentStatus.DONE -> session.project?.takeIf(String::isNotBlank)
    }

    /** Rewrites the stock Claude notification wording into a board-sized phrase. */
    private fun pendingSummary(session: AgentSession): String {
        val raw = session.pendingRequest?.summary?.singleLine(160) ?: return "needs you"
        PERMISSION_PATTERN.find(raw)?.let { return "wants to run ${it.groupValues[1]}" }
        if (raw.contains("waiting for your input", ignoreCase = true)) return "waiting for you"
        return raw
    }

    private fun sessionsSubtitle(
        sessions: List<AgentSession>,
        connections: Map<AgentProvider, ProviderConnectionState>,
        config: AgentsConfig,
    ): String {
        val counts = listOfNotNull(
            sessions.count { it.status == AgentStatus.NEEDS_YOU }
                .takeIf { it > 0 }?.let { "$it waiting" },
            sessions.count { it.status == AgentStatus.WORKING }
                .takeIf { it > 0 }?.let { "$it running" },
            sessions.count { it.status == AgentStatus.ERROR }
                .takeIf { it > 0 }?.let { "$it failed" },
            sessions.count { it.status == AgentStatus.IDLE }
                .takeIf { it > 0 }?.let { "$it idle" },
        )
        if (counts.isNotEmpty()) {
            return counts.joinToString(" · ").take(120)
        }
        val connecting = AgentProvider.values().any { provider ->
            provider.enabledIn(config) &&
                connections[provider]?.state == ConnectionState.CONNECTING
        }
        return if (connecting) "connecting…" else "no sessions"
    }

    private fun emptyRow(config: AgentsConfig): NexusCardLine = NexusCardLine(
        text = if (config.agentdEnabled || config.openClawEnabled) {
            "Nothing running"
        } else {
            "Set up a provider in the phone app"
        },
        sub = if (config.agentdEnabled || config.openClawEnabled) {
            "sessions appear here as soon as an agent starts"
        } else {
            null
        },
        tone = NexusRowTone.DIM,
    )

    private fun problemRow(text: String, sub: String): NexusCardLine =
        NexusCardLine(text = text, sub = sub, tone = NexusRowTone.ALERT, trail = listOf("!"))

    // ------------------------------------------------------------ conversation

    private fun conversationCard(conversation: AgentConversation): NexusCard {
        val session = AgentsRuntime.store.sessions.value
            .firstOrNull { it.key == conversation.sessionKey }
        val rows = when {
            conversation.loading -> listOf(
                NexusCardLine(text = "Reading the conversation…", tone = NexusRowTone.DIM),
            )
            conversation.messages.isEmpty() -> listOf(
                NexusCardLine(
                    text = "Nothing to show yet",
                    sub = "this session has no readable messages",
                    tone = NexusRowTone.DIM,
                ),
            )
            else -> {
                val end = (conversation.messages.size - scrollBack)
                    .coerceIn(1, conversation.messages.size)
                val start = (end - DETAIL_VISIBLE_ROWS).coerceAtLeast(0)
                conversation.messages.subList(start, end).map(::messageRow)
            }
        }
        return card(
            title = session?.displayTitle?.singleLine(110) ?: "Conversation",
            subtitle = conversationSubtitle(session, conversation),
            rows = rows,
            footer = if (scrollBack > 0) {
                "UP/DOWN scroll · BACK to list · $scrollBack older"
            } else {
                "UP/DOWN scroll · BACK to list"
            },
        )
    }

    private fun messageRow(message: AgentMessage): NexusCardLine = NexusCardLine(
        text = message.text.singleLine(238),
        badge = message.role.label,
        tone = NexusRowTone.BODY,
        // The newest message stays brightest while older ones recede.
        selected = false,
    )

    private fun conversationSubtitle(
        session: AgentSession?,
        conversation: AgentConversation,
    ): String {
        val parts = listOfNotNull(
            session?.let { statusPhrase(it) },
            session?.project?.takeIf(String::isNotBlank),
            session?.machineName?.takeIf(String::isNotBlank),
            conversation.messages.size.takeIf { it > 0 }?.let { "$it messages" },
        )
        return parts.joinToString(" · ").take(120).ifBlank { "conversation" }
    }

    private fun statusPhrase(session: AgentSession): String = when (session.status) {
        AgentStatus.NEEDS_YOU -> pendingSummary(session)
        AgentStatus.WORKING -> "working"
        AgentStatus.IDLE -> "idle"
        AgentStatus.ERROR -> "error"
        AgentStatus.DONE -> "finished"
    }

    // ------------------------------------------------------------------ common

    private fun card(
        title: String,
        subtitle: String,
        rows: List<NexusCardLine>,
        footer: String,
    ): NexusCard {
        val keySource = buildString {
            append(title).append('\n').append(subtitle).append('\n').append(footer).append('\n')
            rows.forEach { row ->
                append(row.badge).append('|').append(row.text).append('|')
                append(row.sub).append('|').append(row.tone.wireValue).append('|')
                append(row.selected).append('|').append(row.trail.joinToString(",")).append('\n')
            }
        }
        return NexusCard(
            title = title,
            lines = emptyList(),
            subtitle = subtitle,
            footer = footer,
            contentKey = MessageDigest.getInstance("SHA-256")
                .digest(keySource.toByteArray())
                .joinToString("") { "%02x".format(it) },
            richLines = rows,
            handlesBack = true,
        )
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
        private const val DETAIL_VISIBLE_ROWS = 5
        private const val AGE_TICK_MS = 60_000L
        private val PERMISSION_PATTERN =
            Regex("permission to use ([\\w.-]+)", RegexOption.IGNORE_CASE)
    }
}

private fun AgentProvider.enabledIn(config: AgentsConfig): Boolean = when (this) {
    AgentProvider.CLAUDE -> config.agentdEnabled
    AgentProvider.OPENCLAW -> config.openClawEnabled
}
