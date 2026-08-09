package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionDecisionEngineTest {
    private val persisted = mutableMapOf<String, String>()
    private val engine =
        AttentionDecisionEngine(persisted::get) { key, value -> persisted[key] = value }

    @Test
    fun alertsOncePerDistinctStateAndAgainWhenItChanges() {
        val needs = session(
            AgentStatus.NEEDS_YOU,
            AgentPendingRequest(PendingRequestKind.PERMISSION, "Run command", 42),
        )

        val first = engine.pending(listOf(needs))
        assertEquals(1, first.size)
        engine.commit(first.single())

        assertTrue(engine.pending(listOf(needs)).isEmpty())

        val newRequest = needs.copy(pendingRequest = needs.pendingRequest?.copy(createdAt = 43))
        assertEquals(1, engine.pending(listOf(newRequest)).size)
    }

    @Test
    fun anUndeliveredAlertIsOfferedAgain() {
        val needs = session(
            AgentStatus.NEEDS_YOU,
            AgentPendingRequest(PendingRequestKind.PERMISSION, "Run command", 42),
        )

        // The glasses were unreachable: nothing is committed.
        assertEquals(1, engine.pending(listOf(needs)).size)
        assertEquals(1, engine.pending(listOf(needs)).size)

        engine.commit(engine.pending(listOf(needs)).single())
        assertTrue(engine.pending(listOf(needs)).isEmpty())
    }

    @Test
    fun quietSessionsNeverInterrupt() {
        assertTrue(
            engine.pending(
                listOf(
                    session(AgentStatus.IDLE),
                    session(AgentStatus.WORKING),
                    session(AgentStatus.DONE),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun sameErrorDoesNotAlertTwiceButANewOneDoes() {
        val error = session(AgentStatus.ERROR).copy(statusDetail = "Process failed")
        engine.commit(engine.pending(listOf(error)).single())
        assertTrue(engine.pending(listOf(error)).isEmpty())
        assertEquals(1, engine.pending(listOf(error.copy(statusDetail = "Network failed"))).size)
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
