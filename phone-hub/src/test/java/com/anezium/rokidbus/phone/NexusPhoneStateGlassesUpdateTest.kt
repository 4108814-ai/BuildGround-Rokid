package com.anezium.rokidbus.phone

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexusPhoneStateGlassesUpdateTest {
    private val notConnected = "Glasses aren't connected — reconnect them first, then retry."

    private fun installStateIntent(state: String, message: String? = null, retry: String? = null): Intent =
        Intent().apply {
            putExtra(NexusPhoneState.EXTRA_GLASSES_APP_STATE, state)
            message?.let { putExtra(NexusPhoneState.EXTRA_GLASSES_APP_MESSAGE, it) }
            retry?.let { putExtra(NexusPhoneState.EXTRA_GLASSES_APP_RETRY, it) }
        }

    private fun updateAvailable() = GlassesAppUpdateState.UpdateAvailable(
        installed = NexusSemVersion(1, 0, 0),
        latest = NexusSemVersion(1, 0, 1),
    )

    private fun upToDate() = GlassesAppUpdateState.UpToDate(
        installed = NexusSemVersion(1, 0, 1),
        latest = NexusSemVersion(1, 0, 1),
    )

    @Test
    fun `update banner swaps to the install error message while the banner is showing`() {
        NexusPhoneState.setGlassesAppUpdateState(updateAvailable())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "install"),
        )

        assertEquals(notConnected, NexusPhoneState.glassesUpdateVersionLabel())
    }

    @Test
    fun `update banner swaps to the query error message while the banner is showing`() {
        NexusPhoneState.setGlassesAppUpdateState(updateAvailable())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "query"),
        )

        assertEquals(notConnected, NexusPhoneState.glassesUpdateVersionLabel())
    }

    @Test
    fun `update banner shows the available version when no error is showing`() {
        NexusPhoneState.setGlassesAppUpdateState(updateAvailable())
        NexusPhoneState.updateGlassesAppInstallState(installStateIntent("installed"))

        assertEquals("Update glasses to v1.0.1", NexusPhoneState.glassesUpdateVersionLabel())
    }

    @Test
    fun `an up to date app keeps the banner hidden even during an error`() {
        NexusPhoneState.setGlassesAppUpdateState(upToDate())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "query"),
        )

        assertNull(NexusPhoneState.glassesUpdateVersionLabel())
    }

    @Test
    fun `a query retry error keeps the update action enabled`() {
        NexusPhoneState.setGlassesAppUpdateState(updateAvailable())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "query"),
        )

        assertTrue(NexusPhoneState.glassesUpdateActionEnabled())
    }

    @Test
    fun `an install retry error keeps the update action enabled`() {
        NexusPhoneState.setGlassesAppUpdateState(updateAvailable())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "install"),
        )

        assertTrue(NexusPhoneState.glassesUpdateActionEnabled())
    }

    @Test
    fun `an up to date app disables the update action even during an error`() {
        NexusPhoneState.setGlassesAppUpdateState(upToDate())
        NexusPhoneState.updateGlassesAppInstallState(
            installStateIntent("error", notConnected, "query"),
        )

        assertFalse(NexusPhoneState.glassesUpdateActionEnabled())
    }
}
