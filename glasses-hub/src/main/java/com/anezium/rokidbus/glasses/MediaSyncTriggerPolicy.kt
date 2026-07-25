package com.anezium.rokidbus.glasses

/** What asked for a sync. v1 deliberately has no per-capture trigger. */
enum class MediaSyncTrigger {
    /** The glasses just started charging. */
    CHARGING_EDGE,

    /** The bus link to the phone hub came up while the glasses were already charging. */
    BUS_CONNECT,

    /** The wearer pressed "Sync now" in the phone plugin. */
    MANUAL,
}

data class MediaSyncConditions(
    val linkUp: Boolean,
    val charging: Boolean,
    val hasEligibleFiles: Boolean,
    val cameraSessionActive: Boolean,
    val autoSyncOnCharge: Boolean,
    val syncInProgress: Boolean,
    val storageReadable: Boolean,
)

enum class MediaSyncSkipReason {
    ALREADY_RUNNING,
    CAMERA_ACTIVE,
    STORAGE_PERMISSION,
    LINK_DOWN,
    NOTHING_PENDING,
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
 * Two rules carry the product intent and are checked before anything else:
 * an in-flight sync is never restarted, and a live camera session always wins — photo sync must
 * never compete with the camera for Wi-Fi Direct, which the radio can only give to one group.
 *
 * `hasEligibleFiles` is glasses-side knowledge only ("there is at least one stable capture on
 * disk"). The real pending set is the phone's ledger diff, computed once the link is up; a
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
        if (trigger == MediaSyncTrigger.MANUAL) return MediaSyncTriggerDecision.Start(trigger)
        if (!conditions.autoSyncOnCharge) return skip(MediaSyncSkipReason.AUTO_SYNC_OFF)
        if (!conditions.charging) return skip(MediaSyncSkipReason.NOT_CHARGING)
        return MediaSyncTriggerDecision.Start(trigger)
    }

    private fun skip(reason: MediaSyncSkipReason) = MediaSyncTriggerDecision.Skip(reason)
}
