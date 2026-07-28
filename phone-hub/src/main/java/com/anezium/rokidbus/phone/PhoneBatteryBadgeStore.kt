package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * The wearer's switch for the phone-battery chip in the glasses status row.
 *
 * On by default: the chip is the whole feature, and it is quiet enough that
 * opting out is the exception. The setting lives phone-side because the phone
 * is the sender — turning it off stops the reports *and* pushes an explicit
 * hidden state, since the glasses deliberately never expire a reading (see
 * PhoneBatteryContract).
 */
class PhoneBatteryBadgeStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun addChangeListener(listener: (Boolean) -> Unit): Subscription {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) listener(isEnabled())
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return Subscription {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    fun interface Subscription : AutoCloseable {
        override fun close()
    }

    companion object {
        private const val KEY_ENABLED = "phone_battery_badge"
    }
}
