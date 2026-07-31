package com.anezium.rokidbus.plugin.relay

import android.app.Notification

internal data class RemoteInputShape(val allowsFreeForm: Boolean)

internal data class NotificationActionShape(
    val hasActionIntent: Boolean,
    val remoteInputs: List<RemoteInputShape>,
)

internal object NotificationAdmission {
    fun isRepliableAction(action: NotificationActionShape): Boolean =
        action.hasActionIntent && action.remoteInputs.any(RemoteInputShape::allowsFreeForm)

    fun firstRepliableActionIndex(actions: List<NotificationActionShape>): Int? =
        actions.indexOfFirst(::isRepliableAction).takeIf { it >= 0 }

    /**
     * Everything repliable is admitted unless the wearer silenced that app.
     *
     * This began as an allowlist, and wearing it showed the cost: a message
     * could not reach the glasses until its app had already sent one and been
     * ticked by hand, so the first message from every app was lost by design.
     * The repliable-action test is itself a strong filter — it passes only
     * notifications a human is waiting on an answer to — and that is the one
     * worth having on its own first.
     *
     * A real per-app model, and mirroring notifications that cannot be replied
     * to at all, is what turns this into a full replacement for the ROM's
     * notifications. Next version.
     */
    fun appIsAdmitted(enabled: Boolean, blockedPackages: Set<String>, packageName: String): Boolean =
        enabled && packageName !in blockedPackages
}

internal fun Notification.findRepliableAction(): Notification.Action? =
    actions?.firstOrNull { action ->
        NotificationAdmission.isRepliableAction(
            NotificationActionShape(
                hasActionIntent = action.actionIntent != null,
                remoteInputs = action.remoteInputs.orEmpty().map { input ->
                    RemoteInputShape(input.allowFreeFormInput)
                },
            ),
        )
    }
