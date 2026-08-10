package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class NexusAgentPolicyTest {
    @Test
    fun `blank memory leaves the existing prompt byte identical`() {
        val existingPrompt = NexusAgentPolicy.buildSystemPrompt(
            customPrompt = "Custom assistant.",
            noticeBand = true,
        )

        assertEquals(
            existingPrompt,
            NexusAgentPolicy.buildSystemPrompt(
                customPrompt = "Custom assistant.",
                noticeBand = true,
                memory = " \n\t",
            ),
        )
    }

    @Test
    fun `memory is appended after response rules verbatim`() {
        val memory = "Lives in Paris.\nPrefers metric units; don't summarize this."
        val prompt = NexusAgentPolicy.buildSystemPrompt(memory = memory)

        assertTrue(
            prompt.endsWith(
                "\n\nWhat the user has told you about themselves:\n$memory",
            ),
        )
    }

    @Test
    fun `notice band prompt adds concise answer constraint while legacy prompt does not`() {
        val legacyPrompt = NexusAgentPolicy.buildSystemPrompt(noticeBand = false)
        val noticeBandPrompt = NexusAgentPolicy.buildSystemPrompt(noticeBand = true)

        assertFalse(legacyPrompt.contains(NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE))
        assertTrue(noticeBandPrompt.contains(NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE))
        assertEquals(
            "$legacyPrompt\n- ${NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE}",
            noticeBandPrompt,
        )
    }

    @Test
    fun `photo decision policy contains no legacy transcript triggers`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt().lowercase()

        REMOVED_TRIGGER_PHRASES.forEach { phrase ->
            assertFalse("Legacy trigger remains in prompt: $phrase", prompt.contains(phrase))
        }
        assertFalse(prompt.contains(INCIDENT_WEATHER_TRANSCRIPT))
        assertTrue(prompt.contains("call take_photo"))
        assertTrue(prompt.contains("decide yourself"))
        assertTrue(prompt.contains("at most once per request"))
        assertTrue(prompt.contains("never claim to see the current scene before a successful tool result"))
        assertTrue(prompt.contains("if no image is available"))
        assertTrue(prompt.contains("say so plainly and offer to look again"))
    }

    @Test
    fun `non photo provider prompt denies current scene access without tool syntax`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = emptyList(),
        ).lowercase()

        assertFalse(prompt.contains("take_photo"))
        assertTrue(prompt.contains("cannot take photos or see the current scene"))
        assertTrue(prompt.contains("say you cannot look"))
        assertTrue(prompt.contains("answer from the available context"))
        assertTrue(prompt.contains("never invent tool-call syntax"))
    }

    @Test
    fun `productivity tools add the supplied current local time and save policy`() {
        val now = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]")

        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = now,
            availableToolNames = listOf(SET_REMINDER_TOOL_NAME, TAKE_NOTE_TOOL_NAME),
        )

        assertTrue(prompt.contains("Now: Friday 2026-08-07 11:32 (+02:00 Europe/Paris)"))
        assertTrue(prompt.contains("absolute ISO-8601 local date-time with offset"))
        assertTrue(prompt.contains("Confirm the scheduled time from the tool result"))
        assertTrue(prompt.contains("Never claim a reminder, timer, or note was saved"))
    }

    @Test
    fun `current time and productivity policy are absent without those tools`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]"),
            availableToolNames = listOf(TAKE_PHOTO_TOOL_NAME),
        )

        assertFalse(prompt.contains("Now:"))
        assertFalse(prompt.contains("absolute ISO-8601"))
        assertFalse(prompt.contains("note was saved"))
    }

    @Test
    fun `calendar delete policy uses exact details and protects ambiguous recurrence`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]"),
            availableToolNames = listOf(
                LIST_CALENDAR_EVENTS_TOOL_NAME,
                DELETE_CALENDAR_EVENT_TOOL_NAME,
            ),
        )

        assertTrue(prompt.contains("Now: Friday 2026-08-07 11:32 (+02:00 Europe/Paris)"))
        assertTrue(prompt.contains("call delete_calendar_event directly"))
        assertTrue(prompt.contains("exact title and start"))
        assertTrue(prompt.contains("call list_calendar_events and ask which event"))
        assertTrue(prompt.contains("explicitly requests the whole series"))
        assertTrue(prompt.contains("Never claim a calendar event was created or deleted"))
    }

    private companion object {
        const val INCIDENT_WEATHER_TRANSCRIPT =
            "ouais c'est quoi la météo de paris aujourd'hui"

        val REMOVED_TRIGGER_PHRASES = listOf(
            "what is this",
            "what do you see",
            "c'est quoi",
            "ce que c'est",
            "regarde",
            "lis ça",
            "traduis ça",
            "this build cannot see the current scene yet",
        )
    }
}
