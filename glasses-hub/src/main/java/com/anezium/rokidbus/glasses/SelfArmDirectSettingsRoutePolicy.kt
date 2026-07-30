package com.anezium.rokidbus.glasses

/**
 * Verification policy for Settings intents that may be accepted without reaching their target.
 *
 * Several YodaOS builds accept the private Wireless Debugging fragment intent but redirect to a
 * different Settings surface. Launch success is therefore only a probe; the accessibility tree
 * must confirm the destination before the normal Developer options traversal is skipped.
 */
internal object SelfArmDirectSettingsRoutePolicy {
    fun shouldFallback(
        pending: Boolean,
        startedAt: Long,
        now: Long,
        verificationWindowMs: Long,
    ): Boolean =
        pending &&
            startedAt > 0L &&
            verificationWindowMs >= 0L &&
            now - startedAt >= verificationWindowMs
}
