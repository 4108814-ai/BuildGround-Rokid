package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.TtsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReadAloudTest {
    @Test
    fun `disabled reading produces no speech`() {
        assertNull(
            RelayReadAloud.textFor(
                enabled = false,
                sender = "Alice",
                renderedThread = "Alice: This stays silent",
            ),
        )
    }

    @Test
    fun `only the newest thread message is spoken with its speaker`() {
        assertEquals(
            "Bob: The newest message in full",
            RelayReadAloud.textFor(
                enabled = true,
                sender = "Weekend plans",
                renderedThread = "Alice: The older message\nBob: The newest message in full",
            ),
        )
    }

    @Test
    fun `the notice sender labels a newest message without its own speaker`() {
        assertEquals(
            "Alice: Message without a label",
            RelayReadAloud.textFor(
                enabled = true,
                sender = "Alice",
                renderedThread = "Message without a label",
            ),
        )
    }

    @Test
    fun `speech cap keeps the newest 1024 characters`() {
        val newestWords = "n".repeat(TtsContract.MAX_TEXT_CHARS)
        val spoken = RelayReadAloud.textFor(
            enabled = true,
            sender = "Alice",
            renderedThread = "Alice: old\nAlice: prefix-$newestWords",
        )

        assertEquals(newestWords, spoken)
        assertTrue(spoken!!.length <= TtsContract.MAX_TEXT_CHARS)
    }
}
