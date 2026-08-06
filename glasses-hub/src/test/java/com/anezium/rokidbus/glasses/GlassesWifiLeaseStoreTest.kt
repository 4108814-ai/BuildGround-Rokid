package com.anezium.rokidbus.glasses

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GlassesWifiLeaseStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun reset() {
        context.getSharedPreferences("glasses_wifi_ownership", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `lease survives a new store instance`() {
        val lease = GlassesWifiLease("camera-1", nexusEnabledWifi = true, acquiredAtMillis = 123L)
        GlassesWifiLeaseStore(context).write(lease)

        assertEquals(lease, GlassesWifiLeaseStore(context).read())
    }

    @Test
    fun `clear removes all durable lease evidence`() {
        val store = GlassesWifiLeaseStore(context)
        store.write(GlassesWifiLease("camera-1", nexusEnabledWifi = true, acquiredAtMillis = 123L))

        store.clear()

        assertNull(GlassesWifiLeaseStore(context).read())
    }
}
