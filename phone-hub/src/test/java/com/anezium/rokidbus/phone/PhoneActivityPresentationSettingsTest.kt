package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhoneActivityPresentationSettingsTest {
    @Test
    fun `always expanded is off by default and persists wearer changes`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val settings = PhoneActivityPresentationSettings(context)

        assertFalse(settings.isAlwaysExpanded())
        settings.setAlwaysExpanded(true)
        assertTrue(PhoneActivityPresentationSettings(context).isAlwaysExpanded())
        settings.setAlwaysExpanded(false)
        assertFalse(PhoneActivityPresentationSettings(context).isAlwaysExpanded())
    }
}
