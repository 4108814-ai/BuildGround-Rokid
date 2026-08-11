package com.anezium.rokidbus.phone

import org.json.JSONObject

internal sealed interface RokidNativePointerCommand {
    data object Enter : RokidNativePointerCommand
    data class Move(val deltaX: Float, val deltaY: Float) : RokidNativePointerCommand
    data object MoveEnd : RokidNativePointerCommand
    data object Click : RokidNativePointerCommand
    data object LongPress : RokidNativePointerCommand
    data object Exit : RokidNativePointerCommand
}

internal data class RokidNativePointerMessage(
    val capsCommand: String,
    val payload: JSONObject,
)

/** Hi Rokid's measured `Tools` command vocabulary, kept private to the trusted phone hub. */
internal object RokidNativePointerProtocol {
    const val MODULE = "Tools"
    const val COMMAND_REMOTE_CONTROL = "Tools_SendRemoteControlCmd"
    const val COMMAND_TOUCH_EVENT = "Tools_TouchEvent"
    const val MAPPED_WIDTH = 480f
    const val MAPPED_HEIGHT = 640f

    fun mappedDelta(delta: RemotePointerDelta): RokidNativePointerCommand.Move =
        RokidNativePointerCommand.Move(
            deltaX = (delta.x * MAPPED_WIDTH).toFloat(),
            deltaY = (delta.y * MAPPED_HEIGHT).toFloat(),
        )

    fun encode(command: RokidNativePointerCommand): RokidNativePointerMessage = when (command) {
        RokidNativePointerCommand.Enter -> remoteControl("enterTouch")
        RokidNativePointerCommand.Exit -> remoteControl("exitTouch")
        is RokidNativePointerCommand.Move -> touchEvent(
            type = "moving",
            deltaX = command.deltaX,
            deltaY = command.deltaY,
        )
        RokidNativePointerCommand.MoveEnd -> touchEvent("move_end", 0f, 0f)
        RokidNativePointerCommand.Click -> touchEvent("click")
        RokidNativePointerCommand.LongPress -> touchEvent("long_press", 0f, 0f)
    }

    private fun remoteControl(command: String): RokidNativePointerMessage =
        RokidNativePointerMessage(
            capsCommand = COMMAND_REMOTE_CONTROL,
            payload = JSONObject().put("cmd", command),
        )

    private fun touchEvent(
        type: String,
        deltaX: Float? = null,
        deltaY: Float? = null,
    ): RokidNativePointerMessage {
        val payload = JSONObject()
            .put("finger_num", 1)
            .put("type", type)
        if (deltaX != null && deltaY != null) {
            payload.put("dx", deltaX)
            payload.put("dy", deltaY)
        }
        return RokidNativePointerMessage(COMMAND_TOUCH_EVENT, payload)
    }
}
