package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSettingsStringResolverTest {
    @Test
    fun `installed settings translation is preferred without losing fallbacks`() {
        val candidates = SelfArmSettingsLabelMatcher.candidates(
            SelfArmSettingsLabel.WIRELESS_DEBUGGING,
            resolved = listOf("ワイヤレス デバッグ"),
        )

        assertEquals("ワイヤレス デバッグ", candidates.first())
        assertTrue(candidates.contains("wireless debugging"))
    }

    @Test
    fun `resolved labels support scripts absent from fallback table`() {
        assertTrue(
            SelfArmSettingsLabelMatcher.matches(
                value = "الاشكال وإصلاحه اللاسلكي",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = listOf("الاشكال وإصلاحه اللاسلكي"),
            ),
        )
        assertTrue(
            SelfArmSettingsLabelMatcher.matches(
                value = "ワイヤレス デバッグ",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = listOf("ワイヤレス デバッグ"),
            ),
        )
        assertTrue(
            SelfArmSettingsLabelMatcher.matches(
                value = "Бездротове налагодження",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = listOf("Бездротове налагодження"),
            ),
        )
    }

    @Test
    fun `fallbacks remain accent and punctuation tolerant`() {
        assertTrue(
            SelfArmSettingsLabelMatcher.matches(
                value = "DÉBOGAGE SANS FIL",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = emptyList(),
            ),
        )
        assertTrue(
            SelfArmSettingsLabelMatcher.matches(
                value = "Code d’association Wi‑Fi",
                label = SelfArmSettingsLabel.PAIRING_CODE_LABEL,
                resolved = emptyList(),
            ),
        )
    }

    @Test
    fun `unrelated settings text is rejected`() {
        assertFalse(
            SelfArmSettingsLabelMatcher.matches(
                value = "Mémoire",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = listOf("Wireless debugging"),
            ),
        )
    }

    @Test
    fun `action equality rejects a summary that merely contains the title`() {
        val resolved = listOf("Wireless debugging")

        assertFalse(
            SelfArmSettingsLabelMatcher.matchesExactly(
                value = "Open Wireless debugging settings",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = resolved,
            ),
        )
        assertTrue(
            SelfArmSettingsLabelMatcher.matchesExactly(
                value = "Wireless debugging",
                label = SelfArmSettingsLabel.WIRELESS_DEBUGGING,
                resolved = resolved,
            ),
        )
    }
}
