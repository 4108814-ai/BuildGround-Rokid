package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesRuntimeEvidenceTest {
    @Test
    fun `probe and hub capabilities are valid runtime evidence`() {
        assertEquals("hub_probe", glassesRuntimeEvidenceForPath("/hub/probe"))
        assertEquals(
            "hub_capabilities",
            glassesRuntimeEvidenceForPath(BusPaths.HUB_CAPABILITIES),
        )
        assertNull(glassesRuntimeEvidenceForPath("/unrelated"))
    }

    @Test
    fun `gate one accepts either evidence path while it is recent`() {
        val nowMs = 120_000L

        listOf("/hub/probe", BusPaths.HUB_CAPABILITIES).forEach { path ->
            assertTrue(glassesRuntimeEvidenceForPath(path) != null)
            assertTrue(
                hasRecentGlassesRuntimeEvidence(
                    lastEvidenceAtMs = nowMs - 60_000L,
                    nowMs = nowMs,
                    maxAgeMs = 60_000L,
                ),
            )
        }
        assertFalse(
            hasRecentGlassesRuntimeEvidence(
                lastEvidenceAtMs = nowMs - 60_001L,
                nowMs = nowMs,
                maxAgeMs = 60_000L,
            ),
        )
    }
}
