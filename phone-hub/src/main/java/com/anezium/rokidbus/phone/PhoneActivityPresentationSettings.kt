package com.anezium.rokidbus.phone

import android.content.Context

/** Platform-owned wearer preference; plugins never receive or set this value. */
internal class PhoneActivityPresentationSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    fun isAlwaysExpanded(): Boolean =
        preferences.getBoolean(KEY_ALWAYS_EXPANDED, false)

    fun setAlwaysExpanded(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ALWAYS_EXPANDED, enabled).apply()
    }

    private companion object {
        const val KEY_ALWAYS_EXPANDED = "activity_always_expanded"
    }
}
