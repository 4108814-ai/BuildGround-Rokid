package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.RemotePointerAction
import com.anezium.rokidbus.shared.RemotePointerCommand

internal enum class RemotePointerExecutionResult {
    PERFORMED,
    SERVICE_UNAVAILABLE,
    ACTION_UNAVAILABLE,
    GESTURE_CANCELLED,
    COMMAND_RETIRED,
}

internal class RemotePointerGestureToken(
    internal val completion: (RemotePointerExecutionResult) -> Unit,
)

internal class RemotePointerGestureCompletionGate {
    private var pending: RemotePointerGestureToken? = null

    fun begin(
        completion: (RemotePointerExecutionResult) -> Unit,
    ): RemotePointerGestureToken? {
        if (pending != null) return null
        return RemotePointerGestureToken(completion).also { pending = it }
    }

    fun complete(
        token: RemotePointerGestureToken,
        result: RemotePointerExecutionResult,
    ): Boolean {
        if (pending !== token) return false
        pending = null
        token.completion(result)
        return true
    }

    fun cancel(result: RemotePointerExecutionResult): Boolean {
        val token = pending ?: return false
        pending = null
        token.completion(result)
        return true
    }
}

/** Owns the global cursor overlay and accessibility-backed click gesture. */
internal object RemotePointerController {
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var position = GlassesPointerPosition(0.5, 0.5)
    private val gestureCompletions = RemotePointerGestureCompletionGate()
    private val hideAfterInactivity = Runnable { RemotePointerOverlayRenderer.hide() }

    fun onServiceConnected(owner: AccessibilityService) {
        runOnMain {
            service = owner
            RemotePointerOverlayRenderer.onServiceConnected(owner)
        }
    }

    fun onServiceDestroyed(owner: AccessibilityService) {
        runOnMain {
            if (service !== owner) return@runOnMain
            main.removeCallbacks(hideAfterInactivity)
            gestureCompletions.cancel(RemotePointerExecutionResult.SERVICE_UNAVAILABLE)
            RemotePointerOverlayRenderer.onServiceDestroyed(owner)
            service = null
        }
    }

    fun onLinkLost() {
        runOnMain {
            main.removeCallbacks(hideAfterInactivity)
            RemotePointerOverlayRenderer.hide()
        }
    }

    fun perform(
        command: RemotePointerCommand,
        isStillReserved: () -> Boolean,
        callback: (RemotePointerExecutionResult) -> Unit,
    ) {
        // Always enqueue, even when CXR calls from main. Reservation and enqueue are atomic in the
        // bridge, which preserves sequence order against SPP's reader thread.
        main.post {
            if (!isStillReserved()) {
                callback(RemotePointerExecutionResult.COMMAND_RETIRED)
                return@post
            }
            val owner = service
            if (owner == null) {
                callback(RemotePointerExecutionResult.SERVICE_UNAVAILABLE)
                return@post
            }
            if (command.action == RemotePointerAction.HIDE) {
                main.removeCallbacks(hideAfterInactivity)
                RemotePointerOverlayRenderer.hide()
                callback(RemotePointerExecutionResult.PERFORMED)
                return@post
            }
            val nextPosition = GlassesPointerPosition(
                x = command.x ?: return@post callback(RemotePointerExecutionResult.ACTION_UNAVAILABLE),
                y = command.y ?: return@post callback(RemotePointerExecutionResult.ACTION_UNAVAILABLE),
            )
            position = nextPosition
            if (
                command.action == RemotePointerAction.SHOW ||
                command.action == RemotePointerAction.CLICK ||
                command.action == RemotePointerAction.LONG_PRESS
            ) {
                DisplayWakePolicy.noteUserInteraction()
                DisplayWakePolicy.requestWake(owner, DisplayWakeKind.ACTIVITY, requested = true)
            }
            val point = RemotePointerOverlayRenderer.show(position)
            if (point == null) {
                callback(RemotePointerExecutionResult.ACTION_UNAVAILABLE)
                return@post
            }
            scheduleHide()
            when (command.action) {
                RemotePointerAction.SHOW,
                RemotePointerAction.MOVE,
                RemotePointerAction.MOVE_END,
                -> callback(RemotePointerExecutionResult.PERFORMED)
                RemotePointerAction.CLICK -> dispatchClick(owner, point, callback)
                RemotePointerAction.LONG_PRESS -> dispatchPress(owner, point, callback)
                RemotePointerAction.HIDE -> error("Handled above")
            }
        }
    }

    private fun dispatchClick(
        owner: AccessibilityService,
        point: GlassesPointerPixel,
        callback: (RemotePointerExecutionResult) -> Unit,
    ) = dispatchPress(owner, point, callback, TAP_DURATION_MS)

    private fun dispatchPress(
        owner: AccessibilityService,
        point: GlassesPointerPixel,
        callback: (RemotePointerExecutionResult) -> Unit,
        durationMillis: Long = LONG_PRESS_DURATION_MS,
    ) {
        val token = gestureCompletions.begin(callback)
        if (token == null) {
            callback(RemotePointerExecutionResult.ACTION_UNAVAILABLE)
            return
        }
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()
        val accepted = owner.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureCompletions.complete(token, RemotePointerExecutionResult.PERFORMED)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureCompletions.complete(token, RemotePointerExecutionResult.GESTURE_CANCELLED)
                }
            },
            main,
        )
        if (!accepted) {
            gestureCompletions.complete(token, RemotePointerExecutionResult.ACTION_UNAVAILABLE)
        }
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideAfterInactivity)
        main.postDelayed(hideAfterInactivity, POINTER_IDLE_TIMEOUT_MS)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private const val TAP_DURATION_MS = 40L
    private const val LONG_PRESS_DURATION_MS = 550L
    private const val POINTER_IDLE_TIMEOUT_MS = 8_000L
}
