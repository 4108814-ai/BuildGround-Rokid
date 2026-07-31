package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRepositoryTest {
    @Test
    fun `stable ids are deterministic distinct lowercase sha256 prefixes`() {
        val first = ReplyRepository.stableId("notification-key-one")
        val repeated = ReplyRepository.stableId("notification-key-one")
        val second = ReplyRepository.stableId("notification-key-two")

        assertTrue(first.matches(Regex("[0-9a-f]{20}")))
        assertEquals(first, repeated)
        assertNotEquals(first, second)
    }
}
