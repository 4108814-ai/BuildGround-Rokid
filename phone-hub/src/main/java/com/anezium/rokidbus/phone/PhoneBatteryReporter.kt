package com.anezium.rokidbus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PhoneBatteryContract
import java.util.UUID

/**
 * Pushes the phone's charge to the glasses' status badge.
 *
 * `ACTION_BATTERY_CHANGED` is sticky and chatty — it fires on every percent and
 * on every plug event — so this deduplicates on the value rather than the
 * broadcast. A phone sitting at 47% can emit the intent repeatedly without ever
 * putting a frame on the wire.
 *
 * It also resends on demand, because the badge's state lives on the glasses and
 * dies with their hub. Link-up and the glasses' own capability announcement are
 * both wired to [resend]; without that, a wearer who rebooted their glasses
 * would see no badge until the phone happened to cross a percent.
 *
 * The wearer's toggle ([PhoneBatteryBadgeStore]) gates the *sending*, and
 * turning it off transmits an explicit hidden state rather than going silent:
 * readings never expire glasses-side, so silence would leave the last
 * percentage on the HUD forever. The battery keeps being tracked while
 * disabled, which is what lets re-enabling show a current number immediately.
 */
internal class PhoneBatteryReporter(
    private val context: Context,
    private val send: (BusEnvelope) -> String?,
    private val log: (String) -> Unit,
    initiallyEnabled: Boolean,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { publish(read(it), reason = "changed") }
        }
    }

    private var registered = false

    /**
     * Guards every send. The battery receiver fires on the main thread but
     * [resend] arrives from whatever thread noticed the link come up, and an
     * unsynchronised counter handed the same seq to two messages — on device,
     * the wearer's toggle-off was then dropped as stale by the glasses' replay
     * guard. Sequencing under one lock also makes wire order match seq order
     * per transport.
     */
    private val sendLock = Any()
    private var enabled = initiallyEnabled
    private var seq = 0L
    private var last: PhoneBatteryContract.Reading? = null

    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }.getOrElse {
            log("phone battery receiver registration failed")
            return
        }
        registered = true
        // The registration itself hands back the sticky intent, so the first
        // reading needs no broadcast and no polling.
        sticky?.let { publish(read(it), reason = "initial") }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(sendLock) {
            if (this.enabled == enabled) return
            this.enabled = enabled
            if (enabled) {
                last?.let { transmit(it, "enabled") }
            } else {
                transmitHidden("disabled")
            }
        }
    }

    /** Re-announce the current state — reading or hidden — to freshly-started glasses. */
    fun resend(reason: String) {
        synchronized(sendLock) {
            if (!enabled) {
                // The glasses may hold a reading from before the wearer opted out.
                transmitHidden(reason)
            } else {
                last?.let { transmit(it, reason) }
            }
        }
    }

    private fun publish(reading: PhoneBatteryContract.Reading?, reason: String) {
        synchronized(sendLock) {
            if (reading == null || reading == last) return
            last = reading
            if (enabled) transmit(reading, reason)
        }
    }

    private fun transmit(reading: PhoneBatteryContract.Reading, reason: String) {
        val error = send(envelope(PhoneBatteryContract.toJson(reading, nextSeq())))
        if (error == null) {
            log("phone battery sent level=${reading.level} charging=${reading.charging} reason=$reason")
        }
    }

    private fun transmitHidden(reason: String) {
        val error = send(envelope(PhoneBatteryContract.toHiddenJson(nextSeq())))
        if (error == null) {
            log("phone battery sent hidden reason=$reason")
        }
    }

    /**
     * Monotonic across this hub's restarts, not just within one run.
     *
     * The glasses keep their replay guard as long as *their* process lives, and
     * that lifetime is independent of ours: a phone hub that restarts and
     * counts from 1 again is simply deaf-mutable to glasses that already saw
     * seq 7 — measured on device, every message from the fresh hub was dropped
     * as stale. Seeding from the wall clock makes any restart start higher than
     * the previous incarnation ever reached; the max keeps it strictly
     * increasing within a run even if two sends share a millisecond.
     */
    private fun nextSeq(): Long {
        seq = maxOf(seq + 1, System.currentTimeMillis())
        return seq
    }

    private fun envelope(payload: org.json.JSONObject): BusEnvelope =
        BusEnvelope(
            path = BusPaths.PHONE_BATTERY,
            id = UUID.randomUUID().toString(),
            payload = payload,
        )

    private fun read(intent: Intent): PhoneBatteryContract.Reading? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return PhoneBatteryContract.Reading(
            level = (level * 100 / scale).coerceIn(
                PhoneBatteryContract.MIN_LEVEL,
                PhoneBatteryContract.MAX_LEVEL,
            ),
            // Plugged rather than STATUS_CHARGING: a phone sitting at 100% on the
            // charger reports FULL, and "on the charger" is what the wearer is
            // actually asking when they glance at the badge.
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }
}
