package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PhoneBatteryContract
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The last charge the phone reported, held for the status badge.
 *
 * Sequence-guarded like the pin, and for the same reason: SPP delivery is
 * ordered but re-sends are not, so a link-up resend can arrive behind a fresher
 * reading. A badge that flickered back to an older percentage would be worse
 * than one that lagged.
 *
 * There is no expiry. A phone battery reading does not become wrong because it
 * is old, and blanking the badge on a link drop would tell the wearer their
 * phone died when it is the Bluetooth link that went. It clears only when the
 * glasses hub restarts.
 */
internal object PhoneBatteryController {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var latestSeq = Long.MIN_VALUE
    private var current: PhoneBatteryContract.Reading? = null

    fun reading(): PhoneBatteryContract.Reading? = current

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        listener()
        return { listeners.remove(listener) }
    }

    fun handleEnvelope(envelope: BusEnvelope): Boolean {
        if (envelope.path != BusPaths.PHONE_BATTERY) return false
        runOnMain { apply(envelope) }
        return true
    }

    private fun apply(envelope: BusEnvelope) {
        when (val validation = PhoneBatteryContract.validate(envelope.payload)) {
            is PhoneBatteryContract.ValidationResult.Invalid ->
                log("phone battery rejected code=${validation.reason}")
            is PhoneBatteryContract.ValidationResult.Valid -> {
                if (validation.seq <= latestSeq) {
                    log("phone battery dropped stale seq=${validation.seq}")
                    return
                }
                latestSeq = validation.seq
                if (current == validation.reading) return
                current = validation.reading
                listeners.forEach { listener -> runCatching { listener() } }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
