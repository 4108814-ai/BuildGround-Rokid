package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupJournalFormatterTest {
    private val clock: (Long) -> String = { millis -> "T$millis" }

    @Test
    fun `a line names the side it came from`() {
        assertEquals(
            "T1  glasses  settings_scroll_stuck — developer options",
            SetupJournalFormatter.line(entry(1, true, "settings_scroll_stuck", "developer options"), clock),
        )
        assertEquals(
            "T2  phone  start_requested",
            SetupJournalFormatter.line(entry(2, false, "start_requested", ""), clock),
        )
    }

    @Test
    fun `the shared text leads with the builds that produced it`() {
        val text = SetupJournalFormatter.shareText(
            entries = listOf(entry(1, false, "start_requested", "")),
            phoneVersion = "1.0.48",
            glassesVersion = "1.0.48",
            clock = clock,
        )
        assertTrue(text.startsWith("Rokid Nexus setup log"))
        assertTrue(text.contains("phone 1.0.48 · glasses 1.0.48"))
        assertTrue(text.contains("start_requested"))
    }

    /** An owner sharing an empty log should still send something we can answer. */
    @Test
    fun `an empty trail still produces a readable report`() {
        val text = SetupJournalFormatter.shareText(emptyList(), "1.0.48", "", clock)
        assertTrue(text.contains("glasses unknown"))
        assertTrue(text.contains("(nothing recorded yet)"))
    }

    @Test
    fun `failures are the lines worth colouring`() {
        assertTrue(entry(1, true, "manual_assets_failed", "").isFailure)
        assertTrue(entry(1, true, "settings_scroll_stuck", "").isFailure)
        assertTrue(entry(1, false, "manual_command_refused", "").isFailure)
        assertTrue(entry(1, true, "direct_route_redirected", "").isFailure)
        assertFalse(entry(1, false, "start_requested", "").isFailure)
        assertFalse(entry(1, false, "manual_command_sent", "").isFailure)
    }

    private fun entry(at: Long, glasses: Boolean, code: String, detail: String) =
        SetupJournalEntry(atMillis = at, fromGlasses = glasses, code = code, detail = detail)
}
