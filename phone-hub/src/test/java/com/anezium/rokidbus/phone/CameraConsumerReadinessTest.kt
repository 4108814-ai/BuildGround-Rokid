package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraConsumerReadinessTest {
    private fun principal(
        id: String,
        receivePrefixes: List<String> = listOf(
            "/system/plugin",
            "/camera/session/state",
            "/camera/link/offer",
        ),
    ) = PhonePluginPrincipal(
        "dev.$id",
        ComponentName("dev.$id", "dev.$id.Service"),
        10001,
        "digest-$id",
        PluginDescriptor(
            id,
            id,
            3,
            setOf(PluginCapability.CAMERA),
            receivePrefixes,
            null,
            false,
        ),
    )

    @Test
    fun `readiness recomputes across grants revocation and package changes`() {
        val alpha = principal("alpha")
        val zulu = principal("zulu")
        var installed = listOf(zulu, alpha)
        val states = mutableMapOf<PluginGrantKey, PluginGrantState>()
        val readiness = CameraConsumerReadiness(
            installedPrincipals = { installed },
            grantState = { states[it.grantKey()] ?: PluginGrantState.Pending },
        )

        readiness.recompute()
        assertFalse(readiness.isReady())
        states[zulu.grantKey()] = PluginGrantState.Approved(setOf(PluginCapability.CAMERA))
        states[alpha.grantKey()] = PluginGrantState.Approved(setOf(PluginCapability.CAMERA))
        readiness.recompute()
        assertTrue(readiness.isReady())
        assertEquals("alpha", readiness.resolveApproved()?.descriptor?.id)
        states[alpha.grantKey()] = PluginGrantState.Disabled
        readiness.recompute()
        assertEquals("zulu", readiness.resolveApproved()?.descriptor?.id)
        installed = emptyList()
        readiness.recompute()
        assertFalse(readiness.isReady())
    }

    @Test
    fun `snapshot-only camera grant is not a live camera consumer`() {
        val assistant = principal(
            "assistant",
            listOf(
                "/system/plugin",
                "/camera/snapshot/result",
                "/camera/snapshot/error",
            ),
        )
        val missingLinkOffer = principal(
            "partial",
            listOf("/system/plugin", "/camera/session/state"),
        )
        val lens = principal("lens")
        val approved = listOf(assistant, missingLinkOffer, lens).associate {
            it.grantKey() to PluginGrantState.Approved(setOf(PluginCapability.CAMERA))
        }
        val readiness = CameraConsumerReadiness(
            installedPrincipals = { listOf(assistant, missingLinkOffer, lens) },
            grantState = { approved.getValue(it.grantKey()) },
        )

        assertFalse(readiness.isApprovedCameraConsumer(assistant))
        assertFalse(readiness.isApprovedCameraConsumer(missingLinkOffer))
        assertTrue(readiness.isApprovedCameraConsumer(lens))
        readiness.recompute()
        assertEquals("lens", readiness.resolveApproved()?.descriptor?.id)
    }
}
