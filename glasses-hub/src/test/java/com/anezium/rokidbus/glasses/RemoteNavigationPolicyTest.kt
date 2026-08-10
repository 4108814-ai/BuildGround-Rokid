package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteNavigationPolicyTest {
    @Test
    fun mapsRemoteControlsToAccessibilityFirstStrategies() {
        assertEquals(
            RemoteNavigationStrategy.FOCUS_PREVIOUS,
            RemoteNavigationPolicy.strategy(RemoteNavigationAction.PREVIOUS),
        )
        assertEquals(
            RemoteNavigationStrategy.FOCUS_NEXT,
            RemoteNavigationPolicy.strategy(RemoteNavigationAction.NEXT),
        )
        assertEquals(
            RemoteNavigationStrategy.CLICK_FOCUSED,
            RemoteNavigationPolicy.strategy(RemoteNavigationAction.SELECT),
        )
        assertEquals(
            RemoteNavigationStrategy.GLOBAL_BACK,
            RemoteNavigationPolicy.strategy(RemoteNavigationAction.BACK),
        )
    }
}
