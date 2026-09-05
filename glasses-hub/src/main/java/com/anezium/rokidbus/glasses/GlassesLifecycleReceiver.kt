package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the headless Nexus core when Rokid reports that the glasses became usable again.
 *
 * This receiver never opens an Activity or a surface. The stock launcher and Hi Rokid keep
 * ownership of the visible UI; Nexus only restores its hub and accessibility listener so the
 * triple-tap gesture and background plugins are ready without a manual launcher visit.
 */
class GlassesLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = when (intent.action) {
            GlassesLifecycleSignal.ACTION_LEG_STATUS ->
                intent.getStringExtra(GlassesLifecycleSignal.EXTRA_LEG_STATE)
            GlassesLifecycleSignal.ACTION_TAKE_STATUS ->
                intent.getStringExtra(GlassesLifecycleSignal.EXTRA_TAKE_STATE)
            else -> null
        }
        val reason = GlassesLifecycleSignal.resumeReason(intent.action, state)
        if (reason == null) {
            log("Glasses lifecycle ignored action=${intent.action} state=${state ?: "none"}")
            return
        }

        val appContext = context.applicationContext
        log("Glasses lifecycle resume action=${intent.action} reason=$reason")
        GlassesHub.start(appContext)

        val pendingResult = goAsync()
        AccessibilityRearmWatcher.ensureWatchdog(appContext, reason) {
            pendingResult.finish()
        }
    }
}

internal object GlassesLifecycleSignal {
    const val ACTION_TAKE_STATUS = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
    const val ACTION_LEG_STATUS = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
    const val EXTRA_TAKE_STATE = "glasses_take_state"
    const val EXTRA_LEG_STATE = "glasses_leg_state"

    fun resumeReason(action: String?, state: String?): String? = when {
        action == ACTION_LEG_STATUS && state == "1" -> "temples_unfolded"
        action == ACTION_TAKE_STATUS && state == "1" -> "glasses_worn"
        else -> null
    }
}
