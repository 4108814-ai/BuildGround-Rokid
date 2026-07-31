package com.anezium.rokidbus.glasses

/** Bounds shared by setup-owned Wi-Fi automation and its validated-network hand-off. */
internal object SelfArmWifiAutomationPolicy {
    const val AUTOMATION_TIMEOUT_MS = 45_000L
    const val NETWORK_SETTLE_TIMEOUT_MS = 30_000L
    const val MAX_TOGGLE_ATTEMPTS = 2

    fun shouldAutomate(accessibilityServiceArmed: Boolean, wifiEnabled: Boolean): Boolean =
        accessibilityServiceArmed && !wifiEnabled
}
