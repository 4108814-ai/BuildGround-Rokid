package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDiagnosticsModelTest {
    @Test
    fun `ring evicts the oldest event at capacity`() {
        val ring = RelayDiagnosticRing(capacity = 3)

        (1L..4L).forEach { timestamp ->
            ring.append(
                RelayDiagnosticEvent(
                    timestamp,
                    RelayDiagnosticState.REPAIR,
                    RelayDiagnosticReason.REBIND_REQUESTED,
                ),
            )
        }

        assertEquals(listOf(2L, 3L, 4L), ring.snapshot().map(RelayDiagnosticEvent::wallTimeMs))
    }

    @Test
    fun `repository persists through its seam and restores bounded history`() {
        val persistence = MemoryPersistence()
        val first = RelayDiagnosticsRepository(persistence, capacity = 2)
        first.record(event(1L))
        first.record(event(2L))
        first.record(event(3L))

        val restored = RelayDiagnosticsRepository(persistence, capacity = 2)

        assertEquals(listOf(2L, 3L), restored.snapshot().events.map(RelayDiagnosticEvent::wallTimeMs))
        assertTrue(persistence.saveCount >= 3)
    }

    @Test
    fun `lazy updates keep memory exact but rate-limit disk writes`() {
        val persistence = MemoryPersistence()
        val repository = RelayDiagnosticsRepository(persistence, capacity = 4)
        val baseline = persistence.saveCount

        repository.updateLazily(60_000L, nowMs = 1_000L) { it.copy(lastRawNotificationPostedWallMs = 1_000L) }
        repository.updateLazily(60_000L, nowMs = 2_000L) { it.copy(lastRawNotificationPostedWallMs = 2_000L) }
        repository.updateLazily(60_000L, nowMs = 3_000L) { it.copy(lastRawNotificationPostedWallMs = 3_000L) }

        assertEquals(baseline + 1, persistence.saveCount)
        assertEquals(3_000L, repository.snapshot().lastRawNotificationPostedWallMs)

        // An ordinary record carries the withheld hot fields to disk with it.
        repository.record(event(10L))
        assertEquals(3_000L, RelayDiagnosticsRepository(persistence, capacity = 4).snapshot().lastRawNotificationPostedWallMs)

        // Past the interval, the hot path persists again on its own.
        repository.updateLazily(60_000L, nowMs = 62_000L) { it.copy(lastRawNotificationPostedWallMs = 62_000L) }
        assertEquals(
            62_000L,
            RelayDiagnosticsRepository(persistence, capacity = 4).snapshot().lastRawNotificationPostedWallMs,
        )
    }

    @Test
    fun `persisted free form data cannot enter an event or copied diagnostics`() {
        val unsafe = listOf(
            "10|LISTENER|LISTENER_CONNECTED|0|1",
            "11|LISTENER|sender Alice said secret words|0|1",
            "12|com.chat.private|LISTENER_CONNECTED|0|1",
            "13|LISTENER|LISTENER_CONNECTED|title text|1",
        ).joinToString("\n")

        val events = RelayDiagnosticsRedactor.decodeEvents(unsafe)
        val exported = RelayDiagnosticsRedactor.export(RelayDiagnosticsSnapshot(events = events))

        assertEquals(1, events.size)
        assertTrue(exported.contains("LISTENER|LISTENER_CONNECTED"))
        assertFalse(exported.contains("Alice"))
        assertFalse(exported.contains("secret"))
        assertFalse(exported.contains("com.chat.private"))
        assertFalse(exported.contains("title text"))
    }

    @Test
    fun `decoder discards malformed and negative timestamps`() {
        val encoded = "-1|REPAIR|FAILED|0|0\nnot-a-time|REPAIR|REPAIR_FAILED|0|0"

        assertTrue(RelayDiagnosticsRedactor.decodeEvents(encoded).isEmpty())
    }

    @Test
    fun `non-attempt health states preserve the last repair result`() {
        listOf(
            RelayRepairResult.NO_ACCESS,
            RelayRepairResult.HEALTHY,
            RelayRepairResult.WAITING,
            RelayRepairResult.BACKING_OFF,
        ).forEach { result ->
            val decision = RelayRepairDecision(RelayRepairAction.NONE, result, RelayRepairState())

            assertEquals(
                RelayRepairResult.REBIND_REQUESTED,
                RelayRepairDiagnosticsPolicy.resultAfterDecision(
                    RelayRepairResult.REBIND_REQUESTED,
                    decision,
                ),
            )
        }
    }

    @Test
    fun `actions and terminal outcomes update the last repair result`() {
        val action = RelayRepairDecision(
            RelayRepairAction.REQUEST_REBIND,
            RelayRepairResult.REBIND_REQUESTED,
            RelayRepairState(),
        )
        assertEquals(
            RelayRepairResult.REBIND_REQUESTED,
            RelayRepairDiagnosticsPolicy.resultAfterDecision(RelayRepairResult.NEVER, action),
        )

        listOf(
            RelayRepairResult.CONNECTED,
            RelayRepairResult.CLEAN_RATE_LIMITED,
            RelayRepairResult.FAILED,
            RelayRepairResult.COMMAND_FAILED,
        ).forEach { result ->
            val decision = RelayRepairDecision(RelayRepairAction.NONE, result, RelayRepairState())
            assertEquals(
                result,
                RelayRepairDiagnosticsPolicy.resultAfterDecision(
                    RelayRepairResult.REBIND_REQUESTED,
                    decision,
                ),
            )
        }
    }

    @Test
    fun `unchanged repair evaluations do not consume ring entries`() {
        val previous = RelayDiagnosticEvent(
            wallTimeMs = 10L,
            state = RelayDiagnosticState.REPAIR,
            reason = RelayDiagnosticReason.HEALTHY,
            counter = 0L,
            generation = 2L,
        )

        assertFalse(
            RelayRepairDiagnosticsPolicy.shouldAppend(previous, previous.copy(wallTimeMs = 20L)),
        )
        assertTrue(
            RelayRepairDiagnosticsPolicy.shouldAppend(
                previous,
                previous.copy(wallTimeMs = 20L, generation = 3L),
            ),
        )
    }

    private fun event(timestamp: Long) = RelayDiagnosticEvent(
        timestamp,
        RelayDiagnosticState.GUARDIAN,
        RelayDiagnosticReason.GUARDIAN_BOUND,
    )

    private class MemoryPersistence : RelayDiagnosticsPersistence {
        var value = RelayDiagnosticsSnapshot()
        var saveCount = 0

        override fun load(): RelayDiagnosticsSnapshot = value

        override fun save(snapshot: RelayDiagnosticsSnapshot) {
            value = snapshot
            saveCount += 1
        }
    }
}
