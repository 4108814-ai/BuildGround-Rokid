package com.anezium.rokidbus.glasses

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HudTopInsetTest {
    @Test
    fun `hud top inset defaults clamps and survives controller restore`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("hud_position", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        assertEquals(0, HudTopInset.restore(context))
        HudTopInset.set(context, 100)
        assertEquals(100, preferences.getInt("top_inset_dp", -1))
        assertEquals(100, HudTopInset.restore(context))

        HudTopInset.set(context, 500)
        assertEquals(240, HudTopInset.current(context))
        assertEquals(240, preferences.getInt("top_inset_dp", -1))
        HudTopInset.set(context, -1)
        assertEquals(0, HudTopInset.current(context))

        preferences.edit().putString("top_inset_dp", "garbage").commit()
        assertEquals(0, HudTopInset.restore(context))
        assertEquals(0, preferences.getInt("top_inset_dp", -1))
    }
}
