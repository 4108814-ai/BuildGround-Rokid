package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
