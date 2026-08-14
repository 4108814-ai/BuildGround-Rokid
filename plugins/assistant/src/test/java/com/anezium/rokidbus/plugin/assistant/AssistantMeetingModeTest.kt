package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AssistantMeetingModeTest {
    @Test
    fun `meeting recorder captures segments and builds grounded summary prompt`() {
        var now = ZonedDateTime.of(2026, 8, 14, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
        val recorder = AssistantMeetingRecorder { now }

        assertTrue(recorder.start())
        assertTrue(recorder.append("Иван: срок — пятница."))
        now = now.plusMinutes(20)
        val transcript = recorder.finish()!!

        val prompt = transcript.summaryPrompt()
        assertTrue(prompt.contains("Иван: срок — пятница."))
        assertTrue(prompt.contains("Ничего не придумывай"))
        assertFalse(recorder.active)
    }

    @Test
    fun `meeting commands work in russian and english`() {
        assertTrue(isMeetingStartCommand("Начать совещание"))
        assertTrue(isMeetingStartCommand("meeting mode"))
        assertTrue(isMeetingStopCommand("Закончи совещание"))
        assertTrue(isMeetingStopCommand("end meeting"))
    }
}
