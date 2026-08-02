package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability

class CameraConsumerReadiness(
    private val installedPrincipals: () -> List<PhonePluginPrincipal>,
    private val grantState: (PhonePluginPrincipal) -> PluginGrantState,
) {
    @Volatile private var approvedConsumer: PhonePluginPrincipal? = null

    @Synchronized
    fun recompute(): Boolean {
        val previous = approvedConsumer?.grantKey()
        approvedConsumer = installedPrincipals()
            .asSequence()
            .filter(::isApprovedCameraConsumer)
            .sortedWith(compareBy({ it.descriptor.id }, { it.packageName }))
            .firstOrNull()
        return previous != approvedConsumer?.grantKey()
    }

    fun isReady(): Boolean = approvedConsumer != null

    fun resolveApproved(): PhonePluginPrincipal? = approvedConsumer

    fun isApprovedCameraConsumer(principal: PhonePluginPrincipal): Boolean {
        if (PluginCapability.CAMERA !in principal.descriptor.requestedCapabilities) return false
        if (!speaksLiveCameraContract(principal)) return false
        val state = grantState(principal) as? PluginGrantState.Approved ?: return false
        return PluginCapability.CAMERA in state.capabilities
    }

    private fun speaksLiveCameraContract(principal: PhonePluginPrincipal): Boolean =
        LIVE_CAMERA_RECEIVE_PATHS.all { contractPath ->
            principal.descriptor.receivePrefixes.any { prefix ->
                PathRules.matchesPrefix(contractPath, prefix)
            }
        }

    private companion object {
        val LIVE_CAMERA_RECEIVE_PATHS = setOf(
            BusPaths.CAMERA_SESSION_STATE,
            BusPaths.CAMERA_LINK_OFFER,
        )
    }
}
