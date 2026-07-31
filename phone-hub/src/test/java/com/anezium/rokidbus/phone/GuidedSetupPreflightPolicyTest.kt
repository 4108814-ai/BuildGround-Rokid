package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSetupPreflightPolicyTest {
    @Test
    fun `a unit already armed is not walked through a wizard`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            accessibilityEnabled = true,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = true,
        )
        assertTrue(preflight.alreadyComplete)
        assertNull(preflight.blocking)
    }

    @Test
    fun `the link is asked for before anything that depends on it`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = false,
            accessibilityEnabled = false,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.LINK, preflight.blocking)
    }

    @Test
    fun `satisfied prerequisites are skipped`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            accessibilityEnabled = true,
            wifiReady = false,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.WIFI, preflight.blocking)
    }

    @Test
    fun `developer options never block - Nexus turns them on itself`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            accessibilityEnabled = true,
            wifiReady = true,
            developerOptionsReady = false,
            coreReady = false,
        )
        assertNull(preflight.blocking)
        assertFalse(preflight.alreadyComplete)
        assertFalse(preflight.checks.first { it.id == GuidedCheckId.DEVELOPER }.satisfied)
    }

    @Test
    fun `every check is reported so the owner sees what was inspected`() {
        val preflight = GuidedSetupPreflightPolicy.evaluate(
            linkReady = true,
            accessibilityEnabled = false,
            wifiReady = true,
            developerOptionsReady = true,
            coreReady = false,
        )
        assertEquals(GuidedCheckId.entries.toSet(), preflight.checks.map { it.id }.toSet())
        assertEquals(GuidedCheckId.ACCESSIBILITY, preflight.blocking)
    }
}
