package com.anezium.rokidbus.glasses

import kotlin.math.abs

/**
 * Attempt budget for reviving the command bridge outside a full setup.
 *
 * The bridge is a shell-uid process: no reboot spares it, and its heartbeat file is shell-owned,
 * which scoped storage keeps unreadable from the app. Liveness is therefore inferred, never
 * observed — the boot the bridge was last armed on is compared against the boot the device is
 * running now — and revival attempts are budgeted so a unit that cannot be re-armed asks a few
 * times per opportunity instead of forever.
 */
internal class SelfArmBridgeLivenessPolicy(
    private val clock: () -> Long,
) {
    sealed interface Verdict {
        /** Claimed and counted; the caller owns actually running the re-arm now. */
        data object Attempt : Verdict

        /** Too soon after the previous attempt; nothing was consumed. */
        data class Backoff(val remainingMs: Long) : Verdict

        /** The per-epoch budget is spent; only a reset condition replenishes it. */
        data object CapExhausted : Verdict
    }

    private var attemptsMade = 0
    private var lastAttemptAt = 0L
    private var previousAttemptAt = 0L

    @Synchronized
    fun claimAttempt(): Verdict {
        if (attemptsMade >= MAX_ATTEMPTS_PER_EPOCH) return Verdict.CapExhausted
        if (attemptsMade > 0) {
            val requiredWait = ATTEMPT_BACKOFF_MS[attemptsMade]
            val waited = clock() - lastAttemptAt
            if (waited < requiredWait) return Verdict.Backoff(requiredWait - waited)
        }
        attemptsMade += 1
        previousAttemptAt = lastAttemptAt
        lastAttemptAt = clock()
        return Verdict.Attempt
    }

    /** Refunds a claim the self-arm single-flight rejected: nothing actually ran. */
    @Synchronized
    fun onAttemptRejected() {
        if (attemptsMade == 0) return
        attemptsMade -= 1
        lastAttemptAt = previousAttemptAt
    }

    /**
     * A fresh opportunity earns a fresh budget: a successful arm, a Wi-Fi network appearing,
     * wireless debugging flipping on, the owner opening the setup screen. The two remaining reset
     * conditions — a new boot epoch and a package replacement — need no call site: each restarts
     * the process, and the budget lives in process memory.
     */
    @Synchronized
    fun reset() {
        attemptsMade = 0
        lastAttemptAt = 0L
        previousAttemptAt = 0L
    }

    companion object {
        /**
         * Both sides of the comparison are rounded to whole seconds, so honest readings from the
         * same boot agree to well under this; anything beyond it means elapsedRealtime restarted
         * from zero — a reboot, which no shell process survives.
         */
        const val BOOT_INSTANT_TOLERANCE_MS = 5_000L

        /** Wait required before the next attempt, indexed by attempts already made this epoch. */
        val ATTEMPT_BACKOFF_MS = longArrayOf(
            0L,
            2 * 60_000L,
            8 * 60_000L,
            30 * 60_000L,
            2 * 3_600_000L,
            2 * 3_600_000L,
        )

        const val MAX_ATTEMPTS_PER_EPOCH = 6

        /**
         * True only when an arm is on record from a *different* boot. An absent record is
         * "unknown", not "dead": installs armed before the epoch was first persisted may hold a
         * perfectly healthy bridge, and presuming death would silently skip every deletion.
         */
        fun presumedDead(armedBootInstantMs: Long?, currentBootInstantMs: Long): Boolean =
            armedBootInstantMs != null &&
                abs(currentBootInstantMs - armedBootInstantMs) > BOOT_INSTANT_TOLERANCE_MS
    }
}
