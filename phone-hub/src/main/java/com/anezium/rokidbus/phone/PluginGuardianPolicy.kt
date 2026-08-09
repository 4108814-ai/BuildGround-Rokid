package com.anezium.rokidbus.phone

internal sealed interface GuardianLinkDecision {
    data object EnsureBound : GuardianLinkDecision
    data class ScheduleRelease(val delayMillis: Long) : GuardianLinkDecision
    data object Release : GuardianLinkDecision
    data object None : GuardianLinkDecision
}

internal class GuardianBindLifetimePolicy(
    private val lingerMillis: Long = DEFAULT_LINGER_MILLIS,
) {
    private var linkUp = false
    private var releaseAtMillis: Long? = null

    val isLinkUp: Boolean
        get() = linkUp

    init {
        require(lingerMillis > 0L)
    }

    fun onLinkStateChanged(isUp: Boolean, nowMillis: Long): GuardianLinkDecision {
        if (isUp) {
            linkUp = true
            releaseAtMillis = null
            return GuardianLinkDecision.EnsureBound
        }
        if (!linkUp) return GuardianLinkDecision.None
        linkUp = false
        releaseAtMillis = nowMillis + lingerMillis
        return GuardianLinkDecision.ScheduleRelease(lingerMillis)
    }

    fun onReleaseTimer(nowMillis: Long): GuardianLinkDecision {
        if (linkUp) return GuardianLinkDecision.None
        val releaseAt = releaseAtMillis ?: return GuardianLinkDecision.None
        val remaining = releaseAt - nowMillis
        if (remaining > 0L) return GuardianLinkDecision.ScheduleRelease(remaining)
        releaseAtMillis = null
        return GuardianLinkDecision.Release
    }

    companion object {
        const val DEFAULT_LINGER_MILLIS = 30_000L
    }
}

internal object GuardianBindRetryPolicy {
    private val DELAYS_MILLIS = longArrayOf(
        1_000L,
        5_000L,
        30_000L,
        60_000L,
        5L * 60L * 1_000L,
    )

    fun delayMillis(failureCount: Int): Long {
        require(failureCount > 0)
        return DELAYS_MILLIS[(failureCount - 1).coerceAtMost(DELAYS_MILLIS.lastIndex)]
    }
}
