package com.anezium.rokidbus.plugin.relay

internal object CompanionObservationPolicy {
    fun pathFor(sdkInt: Int): RelayObservationPath = when {
        sdkInt >= 36 -> RelayObservationPath.ASSOCIATION_ID
        sdkInt >= 31 -> RelayObservationPath.ADDRESS
        else -> RelayObservationPath.NONE
    }
}
