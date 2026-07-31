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
     * Being repliable is the whole filter.
     *
     * There was a per-app list here, and wearing it showed what it cost: a
     * message could not reach the glasses until its app had already sent one and
     * been ticked by hand, so the first message from every app was lost by
     * design. The repliable-action test already passes only notifications a
     * human is waiting on an answer to, which is the filter worth having on its
     * own.
     *
     * A real per-app model belongs with the version that also mirrors
     * notifications nobody can reply to — that is the one that would replace the
     * ROM's notifications outright, and it needs rules this does not.
     */
    fun appIsAdmitted(enabled: Boolean): Boolean = enabled
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
