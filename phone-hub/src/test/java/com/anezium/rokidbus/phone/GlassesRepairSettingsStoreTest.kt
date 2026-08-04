package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GlassesRepairSettingsStoreTest {
    @Test
    fun `auto repair is on by default and persists owner changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = GlassesRepairSettingsStore(context)

        assertTrue(store.isAutoRepairEnabled())
        store.setAutoRepairEnabled(false)
        assertFalse(GlassesRepairSettingsStore(context).isAutoRepairEnabled())
        store.setAutoRepairEnabled(true)
        assertTrue(GlassesRepairSettingsStore(context).isAutoRepairEnabled())
    }
}
