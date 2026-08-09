package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionObservationPolicyTest {
    @Test
    fun `Android 16 and newer observe every association by id`() {
        listOf(36, 37, 100).forEach { sdkInt ->
            assertEquals(
                RelayObservationPath.ASSOCIATION_ID,
                CompanionObservationPolicy.pathFor(sdkInt),
            )
        }
    }

    @Test
    fun `Android 12 through 15 observe address-bearing associations`() {
        (31..35).forEach { sdkInt ->
            assertEquals(
                RelayObservationPath.ADDRESS,
                CompanionObservationPolicy.pathFor(sdkInt),
            )
        }
    }

    @Test
    fun `Android 11 does not register presence observation`() {
        assertEquals(
            RelayObservationPath.NONE,
            CompanionObservationPolicy.pathFor(30),
        )
    }
}
