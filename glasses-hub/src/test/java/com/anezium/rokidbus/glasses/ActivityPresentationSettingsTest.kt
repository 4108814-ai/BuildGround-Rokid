package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ActivityPresentationSettingsTest {
    @Test
    fun `platform form-factor preference persists on glasses`() {
        val context = RuntimeEnvironment.getApplication()
        ActivityPresentationSettings.setAlwaysExpanded(context, false)
        assertFalse(ActivityPresentationSettings.alwaysExpanded(context))

        ActivityPresentationSettings.setAlwaysExpanded(context, true)
        assertTrue(ActivityPresentationSettings.alwaysExpanded(context))
    }
}
