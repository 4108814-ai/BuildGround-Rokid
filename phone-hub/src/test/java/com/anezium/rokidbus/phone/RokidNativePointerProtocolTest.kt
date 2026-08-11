package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RokidNativePointerProtocolTest {
    @Test
    fun `normalized phone deltas map to Hi Rokid nominal glasses pixels`() {
        assertEquals(
            RokidNativePointerCommand.Move(48f, -160f),
            RokidNativePointerProtocol.mappedDelta(RemotePointerDelta(0.1, -0.25)),
        )
    }

    @Test
    fun `enter and exit use remote control command in first Caps field`() {
        val enter = RokidNativePointerProtocol.encode(RokidNativePointerCommand.Enter)
        val exit = RokidNativePointerProtocol.encode(RokidNativePointerCommand.Exit)

        assertEquals(RokidNativePointerProtocol.COMMAND_REMOTE_CONTROL, enter.capsCommand)
        assertEquals("enterTouch", enter.payload.getString("cmd"))
        assertEquals(RokidNativePointerProtocol.COMMAND_REMOTE_CONTROL, exit.capsCommand)
        assertEquals("exitTouch", exit.payload.getString("cmd"))
    }

    @Test
    fun `move click end and long press mirror the native touch vocabulary`() {
        val move = RokidNativePointerProtocol.encode(RokidNativePointerCommand.Move(12.5f, -8f))
        assertEquals(RokidNativePointerProtocol.COMMAND_TOUCH_EVENT, move.capsCommand)
        assertEquals(1, move.payload.getInt("finger_num"))
        assertEquals("moving", move.payload.getString("type"))
        assertEquals(12.5, move.payload.getDouble("dx"), 0.0)
        assertEquals(-8.0, move.payload.getDouble("dy"), 0.0)

        val click = RokidNativePointerProtocol.encode(RokidNativePointerCommand.Click).payload
        assertEquals("click", click.getString("type"))
        assertFalse(click.has("dx"))
        assertFalse(click.has("dy"))

        val end = RokidNativePointerProtocol.encode(RokidNativePointerCommand.MoveEnd).payload
        assertEquals("move_end", end.getString("type"))
        assertEquals(0.0, end.getDouble("dx"), 0.0)
        assertEquals(0.0, end.getDouble("dy"), 0.0)

        val longPress = RokidNativePointerProtocol.encode(RokidNativePointerCommand.LongPress).payload
        assertEquals("long_press", longPress.getString("type"))
        assertEquals(0.0, longPress.getDouble("dx"), 0.0)
        assertEquals(0.0, longPress.getDouble("dy"), 0.0)
    }
}
