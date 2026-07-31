package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.MediaSyncMode

/** What asked for a sync. */
enum class MediaSyncTrigger {
    /** The glasses just started charging. */
    CHARGING_EDGE,

    /** The bus link to the phone hub came up. */
    BUS_CONNECT,

    /** A new capture landed in the camera directory. */
    NEW_CAPTURE,

    /** The wearer pressed "Sync now" in the phone plugin. */
    MANUAL,
}

data class MediaSyncConditions(
    val linkUp: Boolean,
    val charging: Boolean,
    val hasEligibleFiles: Boolean,
    val cameraSessionActive: Boolean,
    val mode: MediaSyncMode,
    val syncInProgress: Boolean,
    val storageReadable: Boolean,
)

enum class MediaSyncSkipReason {
    ALREADY_RUNNING,
    CAMERA_ACTIVE,
    STORAGE_PERMISSION,
    LINK_DOWN,
    NOTHING_PENDING,

    /** The wearer chose manual-only, so nothing but the button may start a sync. */
    AUTO_SYNC_OFF,
    NOT_CHARGING,
}

sealed interface MediaSyncTriggerDecision {
    data class Start(val trigger: MediaSyncTrigger) : MediaSyncTriggerDecision
    data class Skip(val reason: MediaSyncSkipReason) : MediaSyncTriggerDecision
}

/**
 * The whole "should we sync right now" question, as one pure function.
 *
 * Two rules carry the product intent and are checked before anything else: an in-flight sync is
 * never restarted, and a live camera session always wins — the transfer now shares one Bluetooth
 * link with the camera's own control traffic and must never crowd it.
 *
 * `hasEligibleFiles` is glasses-side knowledge only ("there is at least one stable capture on
 * disk"). The real pending set is the phone's ledger diff, computed once the session opens; a
 * session that finds nothing to do simply ends as up-to-date.
 */
object MediaSyncTriggerPolicy {
    fun decide(
        trigger: MediaSyncTrigger,
        conditions: MediaSyncConditions,
    ): MediaSyncTriggerDecision {
        if (conditions.syncInProgress) return skip(MediaSyncSkipReason.ALREADY_RUNNING)
        if (conditions.cameraSessionActive) return skip(MediaSyncSkipReason.CAMERA_ACTIVE)
        if (!conditions.storageReadable) return skip(MediaSyncSkipReason.STORAGE_PERMISSION)
        if (!conditions.linkUp) return skip(MediaSyncSkipReason.LINK_DOWN)
        if (!conditions.hasEligibleFiles) return skip(MediaSyncSkipReason.NOTHING_PENDING)
        // The button always works: every mode, charging or not.
        if (trigger == MediaSyncTrigger.MANUAL) return MediaSyncTriggerDecision.Start(trigger)
        return when (conditions.mode) {
            MediaSyncMode.MANUAL -> skip(MediaSyncSkipReason.AUTO_SYNC_OFF)
            MediaSyncMode.CHARGING ->
                if (conditions.charging) {
                    MediaSyncTriggerDecision.Start(trigger)
                } else {
                    skip(MediaSyncSkipReason.NOT_CHARGING)
                }
            MediaSyncMode.ALWAYS -> MediaSyncTriggerDecision.Start(trigger)
        }
    }

    private fun skip(reason: MediaSyncSkipReason) = MediaSyncTriggerDecision.Skip(reason)
}

/** The engine action that accompanies one catalog scan. */
data class MediaSyncAttemptPlan(
    val decision: MediaSyncTriggerDecision,
    val scheduleSettlingRecheck: Boolean,
)

/**
 * Couples trigger eligibility with the catalog's stability state.
 *
 * The re-check is deliberately independent of [MediaSyncTriggerDecision]: a fresh file may still
 * be settling while older files make the catalog non-empty and start an otherwise valid session.
 */
object MediaSyncAttemptPolicy {
    fun plan(
        trigger: MediaSyncTrigger,
        conditions: MediaSyncConditions,
        hasSettlingFiles: Boolean,
    ): MediaSyncAttemptPlan = MediaSyncAttemptPlan(
        decision = MediaSyncTriggerPolicy.decide(trigger, conditions),
        scheduleSettlingRecheck = hasSettlingFiles,
    )
}

/** Keeps a capture-triggered follow-up from being consumed by the session already in flight. */
object MediaSyncDeferredRetryPolicy {
    fun shouldDefer(
        trigger: MediaSyncTrigger,
        reason: MediaSyncSkipReason,
        fromSettlingRecheck: Boolean,
    ): Boolean = reason == MediaSyncSkipReason.ALREADY_RUNNING &&
        (fromSettlingRecheck || trigger == MediaSyncTrigger.NEW_CAPTURE)
}

/** Whether the slow directory fingerprint fallback should inspect captures on this tick. */
object MediaSyncSafetyScanPolicy {
    fun shouldScan(
        mode: MediaSyncMode,
        charging: Boolean,
        consented: Boolean,
        dataLinkUp: Boolean,
        sessionActive: Boolean,
        cameraSessionActive: Boolean,
    ): Boolean {
        if (!consented || !dataLinkUp || sessionActive || cameraSessionActive) return false
        return when (mode) {
            MediaSyncMode.ALWAYS -> true
            MediaSyncMode.CHARGING -> charging
            MediaSyncMode.MANUAL -> false
        }
    }
}

/** Photo bytes require SPP even when the CXR control channel remains connected. */
object MediaSyncLinkPolicy {
    fun isDataPlaneUp(linkState: Int): Boolean =
        linkState and LinkStateBits.SPP_DATA_UP != 0
}
