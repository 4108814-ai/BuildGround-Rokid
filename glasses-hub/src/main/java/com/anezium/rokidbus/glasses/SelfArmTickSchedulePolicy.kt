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
