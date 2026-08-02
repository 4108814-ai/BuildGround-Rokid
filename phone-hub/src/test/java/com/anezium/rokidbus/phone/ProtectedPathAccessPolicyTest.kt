package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPathAccessPolicyTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private fun principal(
        packageName: String,
        pluginId: String,
        digest: String = "same-signer",
    ) = PhonePluginPrincipal(
        packageName,
        ComponentName(packageName, "$packageName.Service"),
        10001,
        digest,
        PluginDescriptor(
            pluginId,
            pluginId,
            3,
            setOf(PluginCapability.CAMERA),
            listOf("/system/plugin", "/camera/session/state", "/camera/link/offer"),
            null,
            false,
        ),
    )

    @Test
    fun `hub keeps camera access while camera principal needs exact enabled grant`() {
        val store = PluginGrantStore(MemoryStorage())
        val granted = principal("dev.camera", "camera.one")
        val sameSignedButUngranted = principal("dev.other", "camera.other")
        val unsignedOther = principal("dev.unsigned", "camera.unsigned", "other-signer")
        store.approve(granted, setOf(PluginCapability.CAMERA))

        listOf(
            BusPaths.CAMERA_SESSION_STATE,
            BusPaths.CAMERA_OVERLAY,
            BusPaths.CAMERA_SNAPSHOT_REQUEST,
            BusPaths.CAMERA_SNAPSHOT_RESULT,
            BusPaths.CAMERA_SNAPSHOT_ERROR,
        )
            .forEach { path ->
                assertTrue(ProtectedPathAccessPolicy.isAllowed(path, true, null, null))
            }
        assertTrue(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.CAMERA_LINK_OFFER,
                false,
                granted,
                store.stateFor(granted),
            ),
        )
        listOf(sameSignedButUngranted, unsignedOther).forEach { ungranted ->
            assertFalse(
                ProtectedPathAccessPolicy.isAllowed(
                    BusPaths.CAMERA_SESSION_STATE,
                    false,
                    ungranted,
                    store.stateFor(ungranted),
                ),
            )
        }
        store.setEnabled(granted, false)
        assertFalse(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.CAMERA_OVERLAY,
                false,
                granted,
                store.stateFor(granted),
            ),
        )
        assertTrue(ProtectedPathAccessPolicy.isAllowed("/plugin/camera.one", false, unsignedOther, null))
    }

    @Test
    fun `an approved media sync plugin drives sync but cannot forge hub traffic`() {
        val store = PluginGrantStore(MemoryStorage())
        val photosync = mediaSyncPrincipal("dev.photosync", "photosync")
        store.approve(photosync, setOf(PluginCapability.MEDIA_SYNC))
        val state = store.stateFor(photosync)

        listOf(BusPaths.MEDIA_SYNC_SETTINGS, BusPaths.MEDIA_SYNC_NOW).forEach { path ->
            assertTrue(ProtectedPathAccessPolicy.isAllowed(path, false, photosync, state))
        }
        listOf(
            BusPaths.MEDIA_SYNC_STATUS,
            BusPaths.MEDIA_SYNC_XFER_FILE_CHUNK,
            BusPaths.MEDIA_SYNC_CONFIG_REQUEST,
            BusPaths.MEDIA_SYNC_CONFIG,
            BusPaths.MEDIA_SYNC_TRIGGER,
            BusPaths.MEDIA_SYNC_STATE,
        ).forEach { path ->
            assertFalse(ProtectedPathAccessPolicy.isAllowed(path, false, photosync, state))
            assertTrue(ProtectedPathAccessPolicy.isAllowed(path, true, null, null))
        }
    }

    @Test
    fun `status delivery needs the grant but is allowed in the receive direction`() {
        val store = PluginGrantStore(MemoryStorage())
        val photosync = mediaSyncPrincipal("dev.photosync", "photosync")
        val other = principal("dev.other", "camera.other")
        store.approve(photosync, setOf(PluginCapability.MEDIA_SYNC))

        assertTrue(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.MEDIA_SYNC_STATUS,
                false,
                photosync,
                store.stateFor(photosync),
                ProtectedPathDirection.RECEIVE,
            ),
        )
        assertFalse(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.MEDIA_SYNC_STATUS,
                false,
                other,
                store.stateFor(other),
                ProtectedPathDirection.RECEIVE,
            ),
        )
    }

    private fun mediaSyncPrincipal(packageName: String, pluginId: String) = PhonePluginPrincipal(
        packageName,
        ComponentName(packageName, "$packageName.Service"),
        10002,
        "same-signer",
        PluginDescriptor(
            pluginId,
            pluginId,
            3,
            setOf(PluginCapability.MEDIA_SYNC),
            listOf("/system/plugin", "/mediasync/status"),
            null,
            false,
        ),
    )
}
