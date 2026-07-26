package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleAudioLeaseArbitratorTest {
    @Test
    fun pluginAndInternalHoldersAreMutuallyExclusive() {
        val arbitrator = SingleAudioLeaseArbitrator<String>()

        assertTrue(arbitrator.tryAcquire("plugin"))
        assertFalse(arbitrator.tryAcquire("internal"))
        assertEquals("plugin", arbitrator.snapshot())
        assertNull(arbitrator.clearIf { it == "internal" })
        assertEquals("plugin", arbitrator.clearIf { it == "plugin" })

        assertTrue(arbitrator.tryAcquire("internal"))
        assertFalse(arbitrator.tryAcquire("plugin"))
        assertEquals("internal", arbitrator.clear())
        assertNull(arbitrator.snapshot())
    }

    @Test
    fun sequenceMutationCanBePerformedUnderTheHolderLock() {
        data class Lease(var seq: Long)
        val arbitrator = SingleAudioLeaseArbitrator<Lease>()
        arbitrator.tryAcquire(Lease(0))

        val first = arbitrator.withActive { lease ->
            lease.seq.also { lease.seq += 1 }
        }
        val second = arbitrator.withActive { lease ->
            lease.seq.also { lease.seq += 1 }
        }

        assertEquals(0L, first)
        assertEquals(1L, second)
    }
}
