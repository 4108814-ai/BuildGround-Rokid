package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Trigger for [MotionSpikeRenderer].
 *
 * Lives in the main source set rather than beside the debug probe receiver for
 * one practical reason: the accessibility overlay window only exists in the
 * armed production hub, and that hub is release-signed, so a debug build
 * cannot be installed over it. Testing the real window means shipping the
 * trigger in the real build. It comes back out before any of this merges.
 */
class MotionSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sequence = intent.getStringExtra("seq").orEmpty().ifEmpty { "loop" }
        log("Motion spike request: $sequence -> ${MotionSpikeRenderer.play(sequence)}")
    }
}
