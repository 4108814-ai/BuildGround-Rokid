package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawClientStateTest {
    @Test
    fun approvalStoreDropsExcessAndEvictsResolvedApprovals() {
        val store = BoundedApprovalStore(maxSize = 2)
        assertTrue(store.put(approval("a1", "s1")))
        assertTrue(store.put(approval("a2", "s2")))
        assertFalse(store.put(approval("a3", "s3")))
        assertEquals(2, store.size())

        assertEquals("a1", store.remove("a1")?.id)
        assertNull(store.remove("missing"))
        assertEquals(1, store.size())
        assertTrue(store.put(approval("a3", "s3")))
    }

    @Test
    fun approvalStoreEvictsApprovalsForRemovedSessions() {
        val store = BoundedApprovalStore(maxSize = 4)
        store.put(approval("a1", "kept"))
        store.put(approval("a2", "removed"))
        store.put(approval("a3", "kept"))

        store.retainSessionKeys(setOf("kept"))

        assertEquals(listOf("a1", "a3"), store.values().map(OpenClawApproval::id))
    }

    @Test
    fun resolvingApprovalRestoresCachedBaseSessionState() {
        val base = AgentSession(
            id = "s1",
            provider = AgentProvider.OPENCLAW,
            title = "Session",
            status = AgentStatus.WORKING,
            statusDetail = "Running",
        )
        val overlaid = OpenClawProtocol.applyApprovals(
            listOf(base),
            listOf(approval("a1", "s1")),
        ).single()
        assertEquals(AgentStatus.NEEDS_YOU, overlaid.status)

        val resolved = OpenClawProtocol.applyApprovals(listOf(base), emptyList()).single()
        assertEquals(AgentStatus.WORKING, resolved.status)
        assertEquals("Running", resolved.statusDetail)
    }

    private fun approval(id: String, sessionKey: String) = OpenClawApproval(
        id = id,
        sessionKey = sessionKey,
        summary = "Run command",
        createdAtMs = null,
    )
}
