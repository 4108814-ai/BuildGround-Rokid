package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneHudPositionStoreTest {
    @Test
    fun `hud top inset defaults clamps and persists wearer changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = PhoneHudPositionStore(context)

        assertEquals(0, store.hudTopInsetDp())
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("hud_top_inset_dp", "garbage")
            .commit()
        assertEquals(0, store.hudTopInsetDp())
        assertEquals(
            0,
            context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
                .getInt("hud_top_inset_dp", -1),
        )
        store.setHudTopInsetDp(100)
        assertEquals(100, PhoneHudPositionStore(context).hudTopInsetDp())
        store.setHudTopInsetDp(500)
        assertEquals(240, PhoneHudPositionStore(context).hudTopInsetDp())
        store.setHudTopInsetDp(-1)
        assertEquals(0, PhoneHudPositionStore(context).hudTopInsetDp())
    }
}
