package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for real ComponentName equality; the JUnit android.jar stubs throw on it.
@RunWith(RobolectricTestRunner::class)
class PluginGuardianSelectionTest {
    @Test
    fun `only installed principals with an approved grant and guardian are selected`() {
        val approved = principal("approved", hasGuardian = true)
        val disabled = principal("disabled", hasGuardian = true)
        val noGuardian = principal("plain", hasGuardian = false)

        val selected = selectApprovedGuardianTargets(
            principals = listOf(approved, disabled, noGuardian),
            grantState = { principal ->
                if (principal === approved || principal === noGuardian) {
                    PluginGrantState.Approved(emptySet())
                } else {
                    PluginGrantState.Disabled
                }
            },
        )

        assertEquals(listOf(approved.grantKey()), selected.map(PluginGuardianTarget::grantKey))
        assertEquals(approved.guardianServiceComponent, selected.single().component)
    }

    private fun principal(id: String, hasGuardian: Boolean): PhonePluginPrincipal {
        val packageName = "dev.example.$id"
        return PhonePluginPrincipal(
            packageName = packageName,
            serviceComponent = ComponentName(packageName, "$packageName.PluginService"),
            uid = id.hashCode(),
            signingDigestSha256 = "digest-$id",
            descriptor = PluginDescriptor(
                id = id,
                displayName = id,
                apiVersion = 3,
                requestedCapabilities = setOf(PluginCapability.SURFACES),
                receivePrefixes = listOf("/system/plugin", "/plugin/$id"),
                settingsActivity = null,
                launchable = true,
            ),
            guardianServiceComponent = if (hasGuardian) {
                ComponentName(packageName, "$packageName.GuardianService")
            } else {
                null
            },
        )
    }
}
