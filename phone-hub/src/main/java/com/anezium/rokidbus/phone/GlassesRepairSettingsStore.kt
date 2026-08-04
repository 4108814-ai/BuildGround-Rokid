package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.shared.GlassesRepairContract

/**
 * The owner's switch for the glasses' boot-time self-repair.
 *
 * The behaviour lives on the glasses — the phone is exactly what is absent at boot — so this
 * store is the phone's copy of record: the settings screen writes it, and the hub re-pushes it
 * on every glasses capabilities announce, so a toggle flipped while the link was down still
 * lands. Defaults to the contract's answer so both sides agree before the first push.
 */
class GlassesRepairSettingsStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isAutoRepairEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTO_REPAIR, GlassesRepairContract.DEFAULT_AUTO_REPAIR)

    fun setAutoRepairEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_REPAIR, enabled).apply()
    }

    companion object {
        private const val KEY_AUTO_REPAIR = "glasses_boot_repair_auto"
    }
}
