package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal phone-UI edge for the glasses pointer.
 *
 * Deltas are fractions of the phone trackpad's width and height: a horizontal delta of `0.1`
 * asks to move one tenth of the glasses display width. The hub coalesces all movement and is the
 * authoritative 30 Hz rate limiter; callers should pass every drag delta without throttling it.
 */
class RemoteTrackpadPublisher(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    fun show() {
        if (!closed.get()) send(RemotePointerPhoneContract.show(appContext))
    }

    fun moveBy(deltaX: Float, deltaY: Float) {
        if (closed.get()) return
        val delta = RemotePointerDeltaPolicy.sanitize(deltaX.toDouble(), deltaY.toDouble()) ?: return
        send(RemotePointerPhoneContract.move(appContext, delta.x, delta.y))
    }

    fun click() {
        if (!closed.get()) send(RemotePointerPhoneContract.click(appContext))
    }

    /** Ends a drag that did not become a click. Call this for the matching pointer-up edge. */
    fun endGesture() {
        if (!closed.get()) send(RemotePointerPhoneContract.moveEnd(appContext))
    }

    fun longPress() {
        if (!closed.get()) send(RemotePointerPhoneContract.longPress(appContext))
    }

    fun hide() {
        if (!closed.get()) send(RemotePointerPhoneContract.hide(appContext))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            send(RemotePointerPhoneContract.hide(appContext))
        }
    }

    private fun send(intent: Intent) {
        if (PhonePointerChannel.deliver(intent)) return
        appContext.sendBroadcast(intent)
    }
}

internal object RemotePointerPhoneContract {
    const val VERSION = 1
    const val ACTION_COMMAND = "com.anezium.rokidbus.phone.remotepointer.COMMAND"

    private const val ACTION_SHOW = "show"
    private const val ACTION_MOVE = "move"
    private const val ACTION_MOVE_END = "move_end"
    private const val ACTION_CLICK = "click"
    private const val ACTION_LONG_PRESS = "long_press"
    private const val ACTION_HIDE = "hide"
    private const val EXTRA_VERSION = "version"
    private const val EXTRA_ACTION = "pointer_action"
    private const val EXTRA_DELTA_X = "delta_x"
    private const val EXTRA_DELTA_Y = "delta_y"

    fun show(context: Context): Intent = actionIntent(context, ACTION_SHOW)

    fun move(context: Context, deltaX: Double, deltaY: Double): Intent {
        val delta = requireNotNull(RemotePointerDeltaPolicy.sanitize(deltaX, deltaY)) {
            "Pointer movement must contain a finite non-zero delta"
        }
        return actionIntent(context, ACTION_MOVE)
            .putExtra(EXTRA_DELTA_X, delta.x)
            .putExtra(EXTRA_DELTA_Y, delta.y)
    }

    fun click(context: Context): Intent = actionIntent(context, ACTION_CLICK)

    fun moveEnd(context: Context): Intent = actionIntent(context, ACTION_MOVE_END)

    fun longPress(context: Context): Intent = actionIntent(context, ACTION_LONG_PRESS)

    fun hide(context: Context): Intent = actionIntent(context, ACTION_HIDE)

    fun parse(intent: Intent): PhonePointerCommand? {
        if (intent.action != ACTION_COMMAND || intent.getIntExtra(EXTRA_VERSION, -1) != VERSION) {
            return null
        }
        return when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_SHOW -> PhonePointerCommand.Show
            ACTION_MOVE -> RemotePointerDeltaPolicy.sanitize(
                intent.getDoubleExtra(EXTRA_DELTA_X, Double.NaN),
                intent.getDoubleExtra(EXTRA_DELTA_Y, Double.NaN),
            )?.let(PhonePointerCommand::Move)
            ACTION_MOVE_END -> PhonePointerCommand.MoveEnd
            ACTION_CLICK -> PhonePointerCommand.Click
            ACTION_LONG_PRESS -> PhonePointerCommand.LongPress
            ACTION_HIDE -> PhonePointerCommand.Hide
            else -> null
        }
    }

    private fun actionIntent(context: Context, action: String): Intent = Intent(ACTION_COMMAND)
        .setPackage(context.packageName)
        .putExtra(EXTRA_VERSION, VERSION)
        .putExtra(EXTRA_ACTION, action)
}

internal sealed interface PhonePointerCommand {
    data object Show : PhonePointerCommand
    data class Move(val delta: RemotePointerDelta) : PhonePointerCommand
    data object MoveEnd : PhonePointerCommand
    data object Click : PhonePointerCommand
    data object LongPress : PhonePointerCommand
    data object Hide : PhonePointerCommand
}

internal data class RemotePointerDelta(val x: Double, val y: Double)

internal object RemotePointerDeltaPolicy {
    fun sanitize(deltaX: Double, deltaY: Double): RemotePointerDelta? {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return null
        val x = deltaX.coerceIn(-MAX_INPUT_DELTA, MAX_INPUT_DELTA)
        val y = deltaY.coerceIn(-MAX_INPUT_DELTA, MAX_INPUT_DELTA)
        return RemotePointerDelta(x, y).takeIf { it.x != 0.0 || it.y != 0.0 }
    }

    private const val MAX_INPUT_DELTA = 1.0
}

internal enum class RemotePointerLinkAction { KEEP, SWITCH_TO_HUB, RESET }

internal object RemotePointerLinkPolicy {
    fun decide(
        connected: Boolean,
        nativePointerAvailable: Boolean,
        nativePointerActive: Boolean,
    ): RemotePointerLinkAction = when {
        !connected -> RemotePointerLinkAction.RESET
        nativePointerActive && !nativePointerAvailable -> RemotePointerLinkAction.SWITCH_TO_HUB
        else -> RemotePointerLinkAction.KEEP
    }
}

internal data class RemotePointerPosition(
    val x: Double = 0.5,
    val y: Double = 0.5,
) {
    fun movedBy(delta: RemotePointerDelta): RemotePointerPosition = RemotePointerPosition(
        x = (x + delta.x).coerceIn(0.0, 1.0),
        y = (y + delta.y).coerceIn(0.0, 1.0),
    )
}

internal data class RemotePointerMoveEmission(
    val position: RemotePointerPosition,
    val delta: RemotePointerDelta,
)

/** Pure state machine behind the phone hub's movement coalescing and 30 Hz bus limit. */
internal class RemotePointerMoveCoalescer(
    private val minimumIntervalMillis: Long = MOVE_INTERVAL_MILLIS,
) {
    private var position = RemotePointerPosition()
    private var pendingDelta = RemotePointerDelta(0.0, 0.0)
    private var pending = false
    private var lastEmissionMillis: Long? = null

    init {
        require(minimumIntervalMillis > 0L)
    }

    fun add(delta: RemotePointerDelta): Boolean {
        val next = position.movedBy(delta)
        pendingDelta = RemotePointerDelta(
            x = pendingDelta.x + delta.x,
            y = pendingDelta.y + delta.y,
        )
        position = next
        pending = true
        return true
    }

    fun currentPosition(): RemotePointerPosition = position

    fun hasPendingMove(): Boolean = pending

    fun delayUntilReady(nowMillis: Long): Long? {
        if (!pending) return null
        val last = lastEmissionMillis ?: return 0L
        return (last + minimumIntervalMillis - nowMillis).coerceAtLeast(0L)
    }

    fun takeReady(nowMillis: Long): RemotePointerMoveEmission? {
        if (delayUntilReady(nowMillis) != 0L) return null
        pending = false
        lastEmissionMillis = nowMillis
        return takeEmission()
    }

    fun takeLatest(): RemotePointerMoveEmission {
        pending = false
        return takeEmission()
    }

    fun clearPending(resetRateLimit: Boolean) {
        pending = false
        pendingDelta = RemotePointerDelta(0.0, 0.0)
        if (resetRateLimit) lastEmissionMillis = null
    }

    fun reset() {
        position = RemotePointerPosition()
        clearPending(resetRateLimit = true)
    }

    private fun takeEmission(): RemotePointerMoveEmission = RemotePointerMoveEmission(
        position = position,
        delta = pendingDelta,
    ).also {
        pendingDelta = RemotePointerDelta(0.0, 0.0)
    }

    companion object {
        /**
         * One update per display frame. The messages are tiny next to the CXR
         * budget, and the ROM's own pointer moves at touch rate — halving that
         * to stay polite is exactly what read as stutter against Hi Rokid.
         */
        const val MOVE_INTERVAL_MILLIS = 16L
    }
}
