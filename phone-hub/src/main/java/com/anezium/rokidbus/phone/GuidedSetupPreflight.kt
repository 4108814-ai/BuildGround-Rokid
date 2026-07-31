package com.anezium.rokidbus.phone

/**
 * What guided setup checks before it asks the owner to do anything.
 *
 * The old manual fallback re-ran the same automaton that had just failed, from the top, and made
 * the owner walk every step again even when most of them were already satisfied. This decides
 * what is genuinely missing, so the screen can skip what is done and ask for one thing at a time.
 */
internal enum class GuidedCheckId {
    /** The phone can reach the glasses at all. Nothing else is worth trying without it. */
    LINK,

    /** The one switch only a human can flip. */
    ACCESSIBILITY,

    /** Pairing rides the local network; without it the ports below are meaningless. */
    WIFI,

    /** Not blocking: Nexus unlocks this itself during the run. Shown so the owner sees why. */
    DEVELOPER,
}

internal data class GuidedCheck(
    val id: GuidedCheckId,
    val satisfied: Boolean,
)

internal data class GuidedPreflight(
    val checks: List<GuidedCheck>,
    /** The first thing standing in the way, or null when the run can start. */
    val blocking: GuidedCheckId?,
    /** True when the observed state already satisfies setup: offer a way out, not a wizard. */
    val alreadyComplete: Boolean,
)

internal object GuidedSetupPreflightPolicy {
    /**
     * [coreReady] is the glasses' own verdict (accessibility on and secure settings granted). When
     * it holds there is nothing left to guide, whatever the other signals say — a unit armed
     * through the documented pm grant path never needed the pairing at all.
     */
    fun evaluate(
        linkReady: Boolean,
        accessibilityEnabled: Boolean,
        wifiReady: Boolean,
        developerOptionsReady: Boolean,
        coreReady: Boolean,
    ): GuidedPreflight {
        val checks = listOf(
            GuidedCheck(GuidedCheckId.LINK, linkReady),
            GuidedCheck(GuidedCheckId.ACCESSIBILITY, accessibilityEnabled),
            GuidedCheck(GuidedCheckId.WIFI, wifiReady),
            GuidedCheck(GuidedCheckId.DEVELOPER, developerOptionsReady),
        )
        if (coreReady) {
            return GuidedPreflight(checks = checks, blocking = null, alreadyComplete = true)
        }
        val blocking = BLOCKING_ORDER.firstOrNull { id ->
            checks.first { it.id == id }.satisfied.not()
        }
        return GuidedPreflight(checks = checks, blocking = blocking, alreadyComplete = false)
    }

    /** Developer options are deliberately absent: Nexus turns them on, it does not ask. */
    private val BLOCKING_ORDER = listOf(
        GuidedCheckId.LINK,
        GuidedCheckId.ACCESSIBILITY,
        GuidedCheckId.WIFI,
    )
}
