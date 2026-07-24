package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentSessionStoreTest {
    @Test
    fun mergesProvidersAndSortsByMissionControlRules() {
        val store = AgentSessionStore()
        store.replaceProvider(
            AgentProvider.CLAUDE,
            listOf(
                session("idle", AgentProvider.CLAUDE, AgentStatus.IDLE, activity = 90),
                session("working-old", AgentProvider.CLAUDE, AgentStatus.WORKING, activity = 100),
                session(
                    "need-new",
                    AgentProvider.CLAUDE,
                    AgentStatus.NEEDS_YOU,
                    pendingAt = 30,
                ),
            ),
            nowMs = 200,
        )
        store.replaceProvider(
            AgentProvider.OPENCLAW,
            listOf(
                session("error", AgentProvider.OPENCLAW, AgentStatus.ERROR, activity = 200),
                session("working-new", AgentProvider.OPENCLAW, AgentStatus.WORKING, activity = 150),
                session(
                    "need-old",
                    AgentProvider.OPENCLAW,
                    AgentStatus.NEEDS_YOU,
                    pendingAt = 20,
                ),
            ),
            nowMs = 200,
        )

        assertEquals(
            listOf("need-old", "need-new", "working-new", "working-old", "idle", "error"),
            store.sessions.value.map(AgentSession::id),
        )
    }

    @Test
    fun dropsOnlyDoneSessionsOlderThanThirtyMinutes() {
        val now = 2_000_000L
        val store = AgentSessionStore()
        store.replaceProvider(
            AgentProvider.CLAUDE,
            listOf(
                session(
                    "expired",
                    AgentProvider.CLAUDE,
                    AgentStatus.DONE,
                    activity = now - AgentSessionStore.DONE_RETENTION_MS - 1,
                ),
                session(
                    "kept",
                    AgentProvider.CLAUDE,
                    AgentStatus.DONE,
                    activity = now - AgentSessionStore.DONE_RETENTION_MS,
                ),
                session("unknown-time", AgentProvider.CLAUDE, AgentStatus.DONE),
            ),
            now,
        )

        assertFalse(store.sessions.value.any { it.id == "expired" })
        assertEquals(listOf("kept", "unknown-time"), store.sessions.value.map(AgentSession::id))
    }

    private fun session(
        id: String,
        provider: AgentProvider,
        status: AgentStatus,
        activity: Long? = null,
        pendingAt: Long? = null,
    ) = AgentSession(
        id = id,
        provider = provider,
        title = id,
        status = status,
        lastActivityAt = activity,
        pendingRequest = pendingAt?.let {
            AgentPendingRequest(PendingRequestKind.PERMISSION, "Approve", it)
        },
    )
}
