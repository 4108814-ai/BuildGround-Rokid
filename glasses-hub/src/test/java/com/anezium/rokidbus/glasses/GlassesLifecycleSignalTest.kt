package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesLifecycleSignalTest {
    @Test
    fun unfoldedTemplesResumeNexus() {
        assertEquals(
            "temples_unfolded",
            GlassesLifecycleSignal.resumeReason(
                GlassesLifecycleSignal.ACTION_LEG_STATUS,
                "1",
            ),
        )
    }

    @Test
    fun wearingGlassesResumesNexus() {
        assertEquals(
            "glasses_worn",
            GlassesLifecycleSignal.resumeReason(
                GlassesLifecycleSignal.ACTION_TAKE_STATUS,
                "1",
            ),
        )
    }

    @Test
    fun foldedOrRemovedStateDoesNotWakeNexus() {
        assertNull(
            GlassesLifecycleSignal.resumeReason(
                GlassesLifecycleSignal.ACTION_LEG_STATUS,
                "0",
            ),
        )
        assertNull(
            GlassesLifecycleSignal.resumeReason(
                GlassesLifecycleSignal.ACTION_TAKE_STATUS,
                "0",
            ),
        )
    }

    @Test
    fun malformedOrUnknownBroadcastDoesNotWakeNexus() {
        assertNull(
            GlassesLifecycleSignal.resumeReason(
                GlassesLifecycleSignal.ACTION_LEG_STATUS,
                null,
            ),
        )
        assertNull(GlassesLifecycleSignal.resumeReason("unexpected", "1"))
        assertNull(GlassesLifecycleSignal.resumeReason(null, "1"))
    }
}
