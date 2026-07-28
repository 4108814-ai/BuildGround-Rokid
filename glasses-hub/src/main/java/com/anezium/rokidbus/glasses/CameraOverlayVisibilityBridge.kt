package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Carries the existing camera-overlay attach/detach edge from `:camera` to the
 * main process where accessibility overlays are owned.
 */
internal object CameraOverlayVisibilityBridge {
    internal const val ACTION =
        "com.anezium.rokidbus.glasses.action.CAMERA_OVERLAY_VISIBILITY"
    internal const val EXTRA_ACTIVE = "active"
    internal const val EXTRA_TOKEN = "token"

    data class Edge(val token: IBinder, val active: Boolean)

    fun report(context: Context, token: IBinder, active: Boolean) {
        context.sendBroadcast(intent(context, token, active))
    }

    internal fun intent(context: Context, token: IBinder, active: Boolean): Intent =
        Intent(context, CameraOverlayVisibilityReceiver::class.java)
            .setAction(ACTION)
            .putExtras(
                Bundle().apply {
                    putBoolean(EXTRA_ACTIVE, active)
                    putBinder(EXTRA_TOKEN, token)
                },
            )

    fun read(intent: Intent): Edge? {
        if (intent.action != ACTION || !intent.hasExtra(EXTRA_ACTIVE)) return null
        val token = intent.extras?.getBinder(EXTRA_TOKEN) ?: return null
        return Edge(token, intent.getBooleanExtra(EXTRA_ACTIVE, false))
    }
}

/**
 * Reference-counts visible camera views by their process-owned binder token.
 * Binder death supplies the detach edge that a crashed `:camera` process
 * cannot send itself.
 */
internal class CameraOverlayVisibilityRegistry(
    private val publish: (Boolean) -> Unit,
) {
    private val deathRecipients = mutableMapOf<IBinder, IBinder.DeathRecipient>()

    fun update(edge: CameraOverlayVisibilityBridge.Edge) {
        val nextVisibility = synchronized(this) {
            val wasVisible = deathRecipients.isNotEmpty()
            if (edge.active) {
                attach(edge.token)
            } else {
                detach(edge.token, unlink = true)
            }
            val isVisible = deathRecipients.isNotEmpty()
            isVisible.takeIf { it != wasVisible }
        }
        nextVisibility?.let(publish)
    }

    internal fun tokenDied(token: IBinder) {
        val becameHidden = synchronized(this) {
            val wasVisible = deathRecipients.isNotEmpty()
            detach(token, unlink = false)
            wasVisible && deathRecipients.isEmpty()
        }
        if (becameHidden) publish(false)
    }

    private fun attach(token: IBinder) {
        if (token in deathRecipients) return
        val recipient = IBinder.DeathRecipient { tokenDied(token) }
        if (runCatching { token.linkToDeath(recipient, 0) }.isSuccess) {
            deathRecipients[token] = recipient
        }
    }

    private fun detach(token: IBinder, unlink: Boolean) {
        val recipient = deathRecipients.remove(token) ?: return
        if (unlink) runCatching { token.unlinkToDeath(recipient, 0) }
    }
}

/**
 * Manifest-owned by the default app process. The receiver is not exported, so
 * only this application's camera process can drive the visibility edge.
 */
internal class CameraOverlayVisibilityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CameraOverlayVisibilityBridge.read(intent)
            ?.let(registry::update)
    }

    private companion object {
        val registry = CameraOverlayVisibilityRegistry(
            ActivityController::setCameraOverlayActive,
        )
    }
}
