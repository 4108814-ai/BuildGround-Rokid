package com.anezium.rokidbus.glasses

import android.content.Context
import com.anezium.rokidbus.shared.GlassesRepairContract

/**
 * The owner's boot-repair switch and the one-popup-per-boot latch.
 *
 * Both live in the wireless-bootstrap prefs file because they share its lifecycle: the switch
 * must hold with the phone absent at boot, and the latch is keyed by the same boot instant the
 * arm epoch uses — a hub restart keeps the instant, so the popup cannot re-fire mid-boot, while
 * a reboot changes it, which is exactly when one fresh attempt is allowed again.
 */
internal object SelfArmBootRepairStore {
    private const val PREFS_NAME = "selfarm_wireless"
    private const val KEY_AUTO_REPAIR_ENABLED = "boot_repair_auto_enabled"
    private const val KEY_ATTEMPT_BOOT_INSTANT = "boot_repair_attempt_boot_instant"

    /** Defaults to the contract's answer so glasses that never heard the phone match its toggle. */
    fun isAutoRepairEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_REPAIR_ENABLED, GlassesRepairContract.DEFAULT_AUTO_REPAIR)

    fun setAutoRepairEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_REPAIR_ENABLED, enabled).apply()
    }

    fun hasAttemptedThisBoot(context: Context): Boolean {
        val recorded = prefs(context).getLong(KEY_ATTEMPT_BOOT_INSTANT, 0L).takeIf { it != 0L }
            ?: return false
        // Same-boot comparison as the arm epoch: only a recording from a different boot frees the
        // latch, with the same tolerance for honest readings of one boot disagreeing slightly.
        return !SelfArmBridgeLivenessPolicy.presumedDead(
            recorded,
            SelfArmBridgeLivenessStore.currentBootInstantMillis(),
        )
    }

    fun recordBootAttempt(context: Context) {
        // commit(), not apply(): the Settings automation this latch guards starts immediately
        // after, and losing the write to a process death would buy the wearer a second popup.
        prefs(context).edit()
            .putLong(KEY_ATTEMPT_BOOT_INSTANT, SelfArmBridgeLivenessStore.currentBootInstantMillis())
            .commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
