package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract

/** Platform-owned wearer preference; plugins never receive or set this value. */
internal class PhoneHudPositionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    fun hudTopInsetDp(): Int {
        val stored = runCatching {
            preferences.getInt(
                KEY_HUD_TOP_INSET_DP,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            )
        }.getOrNull()
        val cleanValue = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(
            stored ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
        )
        if (stored == null || stored != cleanValue) {
            preferences.edit().putInt(KEY_HUD_TOP_INSET_DP, cleanValue).apply()
        }
        return cleanValue
    }

    fun setHudTopInsetDp(value: Int) {
        preferences.edit()
            .putInt(
                KEY_HUD_TOP_INSET_DP,
                PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(value),
            )
            .apply()
    }

    private companion object {
        const val KEY_HUD_TOP_INSET_DP = "hud_top_inset_dp"
    }
}
