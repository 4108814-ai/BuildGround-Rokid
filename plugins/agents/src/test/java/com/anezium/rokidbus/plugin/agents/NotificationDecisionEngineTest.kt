package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDecisionEngineTest {
    @Test
    fun notifiesOnlyOnTransitionAndDedupesPersistedFingerprint() {
        val persisted = mutableMapOf<String, String>()
        val engine = NotificationDecisionEngine(persisted::get) { key, value -> persisted[key] = value }
        val idle = session(AgentStatus.IDLE)
        val needs = session(
            AgentStatus.NEEDS_YOU,
            AgentPendingRequest(PendingRequestKind.PERMISSION, "Run command", 42),
        )

        assertEquals(1, engine.transitions(listOf(idle), listOf(needs)).size)
        assertTrue(engine.transitions(listOf(needs), listOf(needs)).isEmpty())
        assertTrue(engine.transitions(listOf(idle), listOf(needs)).isEmpty())

        val newRequest = needs.copy(
            pendingRequest = needs.pendingRequest?.copy(createdAt = 43),
        )
        assertEquals(1, engine.transitions(listOf(idle), listOf(newRequest)).size)
    }

    @Test
    fun sameErrorDoesNotNotifyTwice() {
        val persisted = mutableMapOf<String, String>()
        val engine = NotificationDecisionEngine(persisted::get) { key, value -> persisted[key] = value }
        val idle = session(AgentStatus.IDLE)
        val error = session(AgentStatus.ERROR).copy(statusDetail = "Process failed")
        assertEquals(1, engine.transitions(listOf(idle), listOf(error)).size)
        assertTrue(engine.transitions(listOf(idle), listOf(error)).isEmpty())
        assertEquals(
            1,
            engine.transitions(
                listOf(idle),
                listOf(error.copy(statusDetail = "Network failed")),
            ).size,
        )
    }

    private fun session(
        status: AgentStatus,
        pending: AgentPendingRequest? = null,
    ) = AgentSession(
        id = "one",
        provider = AgentProvider.CLAUDE,
        title = "One",
        status = status,
        pendingRequest = pending,
    )
}
