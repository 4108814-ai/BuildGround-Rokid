package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.phone.mediasync.MediaSyncSettingsStore
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MediaSyncSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `capture filters load with product defaults`() {
        clearPreferences()

        assertEquals(MediaSyncSettings(), MediaSyncSettingsStore(context).loadSettings())
    }

    @Test
    fun `capture filters persist with the rest of the settings`() {
        clearPreferences()
        val expected = MediaSyncSettings(
            mode = MediaSyncMode.ALWAYS,
            deleteAfterSync = true,
            syncNormalPhotos = false,
            syncArPhotos = true,
            syncNormalVideos = true,
            syncArVideos = true,
        )

        MediaSyncSettingsStore(context).saveSettings(expected)

        assertEquals(expected, MediaSyncSettingsStore(context).loadSettings())
    }

    private fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_media_sync"
    }
}
