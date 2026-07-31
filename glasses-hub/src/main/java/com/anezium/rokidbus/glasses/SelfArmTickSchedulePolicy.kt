package com.anezium.rokidbus.glasses

/**
 * Coalesces Accessibility wake-ups without allowing a noisy Settings build to starve progress.
 *
 * A new event may bring the next tick forward, but can never postpone a tick that is already
 * queued. The previous implementation removed and reposted the callback on every event, so a
 * stream of TYPE_WINDOW_CONTENT_CHANGED events could defer the automator forever.
 */
internal object SelfArmTickSchedulePolicy {
    const val NONE = 0L

    fun nextScheduledAt(
        existingAt: Long,
        requestedAt: Long,
    ): Long =
        when {
            existingAt <= NONE -> requestedAt
            requestedAt < existingAt -> requestedAt
            else -> existingAt
        }
}

/**
 * A pairing that is genuinely in flight suspends the setup timeouts.
 *
 * The local bootstrap takes 60–110 s on real hardware while writing nothing, so both the
 * pairing-dialog hold (60 s) and the run deadline (110 s) used to fire on top of a pairing that
 * was about to succeed, and the wearer was told setup had stopped. Suspension is bounded: a
 * worker that hangs past [maxGraceMillis] stops protecting the run, so a stuck pairing still ends
 * in a real, retryable failure instead of holding the Settings screen forever.
 */
internal object SelfArmPairingGracePolicy {
    fun suspendsExpiry(
        workerAlive: Boolean,
        nowMillis: Long,
        pairingStartedAtMillis: Long,
        maxGraceMillis: Long,
    ): Boolean =
        workerAlive &&
            pairingStartedAtMillis > 0L &&
            nowMillis >= pairingStartedAtMillis &&
            nowMillis - pairingStartedAtMillis < maxGraceMillis
}

internal object SelfArmHeartbeatPolicy {
    fun shouldHeartbeat(
        sessionCurrent: Boolean,
        automatonActive: Boolean,
        workerAlive: Boolean,
        nowMillis: Long,
        lastHeartbeatMillis: Long,
        cadenceMillis: Long,
        force: Boolean = false,
    ): Boolean =
        sessionCurrent &&
            (automatonActive || workerAlive) &&
            (
                force ||
                    lastHeartbeatMillis <= 0L ||
                    nowMillis - lastHeartbeatMillis >= cadenceMillis
            )
}
