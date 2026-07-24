package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentSessionStore {
    private val providerSessions = AgentProvider.values().associateWith {
        linkedMapOf<String, AgentSession>()
    }.toMutableMap()
    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val _connections = MutableStateFlow(
        AgentProvider.values().associateWith { ProviderConnectionState() },
    )

    val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()
    val connections: StateFlow<Map<AgentProvider, ProviderConnectionState>> =
        _connections.asStateFlow()

    @Synchronized
    fun replaceProvider(
        provider: AgentProvider,
        sessions: List<AgentSession>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val replacement = linkedMapOf<String, AgentSession>()
        sessions.filter { it.provider == provider }.forEach { replacement[it.id] = it }
        providerSessions[provider] = replacement
        publish(nowMs)
    }

    @Synchronized
    fun upsert(session: AgentSession, nowMs: Long = System.currentTimeMillis()) {
        providerSessions.getValue(session.provider)[session.id] = session
        publish(nowMs)
    }

    @Synchronized
    fun remove(
        provider: AgentProvider,
        sessionId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        providerSessions.getValue(provider).remove(sessionId)
        publish(nowMs)
    }

    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        publish(nowMs)
    }

    @Synchronized
    fun setConnection(
        provider: AgentProvider,
        state: ConnectionState,
        detail: String? = null,
    ) {
        _connections.value = _connections.value.toMutableMap().apply {
            put(provider, ProviderConnectionState(state, detail))
        }
    }

    private fun publish(nowMs: Long) {
        val retained = providerSessions.values
            .asSequence()
            .flatMap { it.values.asSequence() }
            .filterNot { session ->
                session.status == AgentStatus.DONE &&
                    session.lastActivityAt?.let { activity ->
                        nowMs >= activity && nowMs - activity > DONE_RETENTION_MS
                    } == true
            }
            .sortedWith(SESSION_COMPARATOR)
            .toList()
        _sessions.value = retained
    }

    companion object {
        const val DONE_RETENTION_MS = 30 * 60 * 1_000L

        private val STATUS_RANK = mapOf(
            AgentStatus.NEEDS_YOU to 0,
            AgentStatus.WORKING to 1,
            AgentStatus.IDLE to 2,
            AgentStatus.ERROR to 3,
            AgentStatus.DONE to 4,
        )

        private val SESSION_COMPARATOR = Comparator<AgentSession> { left, right ->
            val rank = STATUS_RANK.getValue(left.status).compareTo(STATUS_RANK.getValue(right.status))
            if (rank != 0) {
                rank
            } else {
                val withinStatus = when (left.status) {
                    AgentStatus.NEEDS_YOU -> compareValues(
                        left.pendingRequest?.createdAt ?: Long.MAX_VALUE,
                        right.pendingRequest?.createdAt ?: Long.MAX_VALUE,
                    )
                    AgentStatus.WORKING,
                    AgentStatus.IDLE,
                    AgentStatus.ERROR,
                    AgentStatus.DONE,
                    -> compareValues(
                        right.lastActivityAt ?: Long.MIN_VALUE,
                        left.lastActivityAt ?: Long.MIN_VALUE,
                    )
                }
                if (withinStatus != 0) {
                    withinStatus
                } else {
                    left.displayTitle.compareTo(right.displayTitle, ignoreCase = true)
                }
            }
        }
    }
}

object AgentsRuntime {
    val store = AgentSessionStore()
}
