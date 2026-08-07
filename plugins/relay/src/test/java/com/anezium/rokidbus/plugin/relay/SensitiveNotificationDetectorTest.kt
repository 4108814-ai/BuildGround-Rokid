package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveNotificationDetectorTest {
    @Test
    fun `exact resolved string matches title or text`() {
        val resolved = listOf("System redaction marker")

        assertTrue(
            SensitiveNotificationDetector.isRedacted(
                title = "System redaction marker",
                text = "Message",
                resolvedStrings = resolved,
            ),
        )
        assertTrue(
            SensitiveNotificationDetector.isRedacted(
                title = "Messages",
                text = "System redaction marker",
                resolvedStrings = resolved,
            ),
        )
    }

    @Test
    fun `different case or surrounding text does not match`() {
        val resolved = listOf("System redaction marker")

        assertFalse(
            SensitiveNotificationDetector.isRedacted(
                title = "system redaction marker",
                text = "Android: System redaction marker",
                resolvedStrings = resolved,
            ),
        )
    }

    @Test
    fun `null and blank content do not match`() {
        assertFalse(
            SensitiveNotificationDetector.isRedacted(
                title = null,
                text = "   ",
                resolvedStrings = listOf("", "   "),
            ),
        )
    }

    @Test
    fun `known English message is used only when system strings are unavailable`() {
        assertTrue(
            SensitiveNotificationDetector.isRedacted(
                title = "Messages",
                text = SensitiveNotificationDetector.ENGLISH_REDACTION_MESSAGE,
                resolvedStrings = emptyList(),
            ),
        )
        assertFalse(
            SensitiveNotificationDetector.isRedacted(
                title = "Messages",
                text = SensitiveNotificationDetector.ENGLISH_REDACTION_MESSAGE,
                resolvedStrings = listOf("System redaction marker"),
            ),
        )
    }
}
