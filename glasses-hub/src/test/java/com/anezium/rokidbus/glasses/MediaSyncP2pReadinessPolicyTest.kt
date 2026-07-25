package com.anezium.rokidbus.glasses

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSyncP2pReadinessPolicyTest {
    @Test
    fun `only the enabled state permits group creation`() {
        assertEquals(
            MediaSyncP2pReadiness.READY,
            MediaSyncP2pReadinessPolicy.readiness(WifiP2pManager.WIFI_P2P_STATE_ENABLED),
        )
    }

    @Test
    fun `a disabled framework waits`() {
        // The crux of the first device failures: station Wi-Fi on but the P2P framework still
        // disabled, so a create would land in P2pDisabledState and return reason=0.
        assertEquals(
            MediaSyncP2pReadiness.WAIT,
            MediaSyncP2pReadinessPolicy.readiness(WifiP2pManager.WIFI_P2P_STATE_DISABLED),
        )
    }

    @Test
    fun `an unknown state waits rather than guessing ready`() {
        assertEquals(MediaSyncP2pReadiness.WAIT, MediaSyncP2pReadinessPolicy.readiness(-1))
        assertEquals(MediaSyncP2pReadiness.WAIT, MediaSyncP2pReadinessPolicy.readiness(99))
    }

    @Test
    fun `the wait budget matches the camera link's`() {
        assertEquals(16, MediaSyncGroup.P2P_WAIT_ATTEMPTS)
        assertEquals(750L, MediaSyncGroup.P2P_WAIT_INTERVAL_MS)
    }
}
