package com.anezium.rokidbus.phone

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubStartupPolicyTest {
    @Test
    fun `boot and package replacement restore an authorized enabled hub`() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            assertTrue(
                HubStartupPolicy.shouldStart(
                    action = action,
                    hubEnabled = true,
                    hasSavedAuthorization = true,
                    canRunHub = true,
                ),
            )
        }
    }

    @Test
    fun `automatic restart respects every user and permission gate`() {
        fun shouldStart(
            action: String? = Intent.ACTION_BOOT_COMPLETED,
            hubEnabled: Boolean = true,
            hasSavedAuthorization: Boolean = true,
            canRunHub: Boolean = true,
        ) = HubStartupPolicy.shouldStart(
            action = action,
            hubEnabled = hubEnabled,
            hasSavedAuthorization = hasSavedAuthorization,
            canRunHub = canRunHub,
        )

        assertFalse(shouldStart(action = Intent.ACTION_SCREEN_ON))
        assertFalse(shouldStart(hubEnabled = false))
        assertFalse(shouldStart(hasSavedAuthorization = false))
        assertFalse(shouldStart(canRunHub = false))
    }
}
