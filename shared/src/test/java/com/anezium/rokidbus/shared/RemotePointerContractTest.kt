package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePointerContractTest {
    private val streamId = "pointer_0123456789abcdef"

    @Test
    fun `pointer uses reserved core paths`() {
        assertEquals("/core/pointer/command", RemotePointerContract.COMMAND_PATH)
        assertEquals("/core/pointer/result", RemotePointerContract.RESULT_PATH)
        assertTrue(RemotePointerContract.COMMAND_PATH.startsWith("/core/"))
    }

    @Test
    fun `positioned commands and hide round trip`() {
        listOf(
            RemotePointerAction.SHOW,
            RemotePointerAction.MOVE,
            RemotePointerAction.MOVE_END,
            RemotePointerAction.CLICK,
            RemotePointerAction.LONG_PRESS,
        ).forEachIndexed { index, action ->
            val command = RemotePointerCommand(streamId, index + 1L, action, 0.125, 0.75)
            assertEquals(
                command,
                RemotePointerContract.parseCommand(RemotePointerContract.command(command)),
            )
        }
        val hide = RemotePointerCommand(streamId, 4L, RemotePointerAction.HIDE)
        assertEquals(hide, RemotePointerContract.parseCommand(RemotePointerContract.command(hide)))
        assertNull(
            RemotePointerContract.parseCommand(
                RemotePointerContract.command(hide).put("x", 0.5).put("y", 0.5),
            ),
        )
    }

    @Test
    fun `coordinates reject missing non numeric and out of range values`() {
        val valid = RemotePointerContract.command(
            RemotePointerCommand(streamId, 1L, RemotePointerAction.MOVE, 0.1, 0.9),
        )

        val missingX = JSONObject(valid.toString()).apply { remove("x") }
        assertNull(RemotePointerContract.parseCommand(missingX))
        assertNull(
            RemotePointerContract.parseCommand(JSONObject(valid.toString()).put("x", "0.1")),
        )
        assertNull(RemotePointerContract.parseCommand(JSONObject(valid.toString()).put("x", -0.01)))
        assertNull(RemotePointerContract.parseCommand(JSONObject(valid.toString()).put("y", 1.01)))
    }

    @Test
    fun `command envelope rejects wrong version stream sequence and action`() {
        val command = RemotePointerContract.command(
            RemotePointerCommand(streamId, 1L, RemotePointerAction.CLICK, 0.5, 0.5),
        )
        assertNull(RemotePointerContract.parseCommand(JSONObject(command.toString()).put("version", 2)))
        assertNull(RemotePointerContract.parseCommand(JSONObject(command.toString()).put("streamId", "short")))
        assertNull(RemotePointerContract.parseCommand(JSONObject(command.toString()).put("sequence", 0)))
        assertNull(
            RemotePointerContract.parseCommand(
                JSONObject(command.toString()).put(
                    "sequence",
                    RemotePointerContract.MAX_SAFE_SEQUENCE + 1L,
                ),
            ),
        )
        assertNull(RemotePointerContract.parseCommand(JSONObject(command.toString()).put("action", "shell")))
    }

    @Test
    fun `successful and failed results round trip and stay consistent`() {
        val success = RemotePointerResult(streamId, 1L, RemotePointerAction.MOVE)
        val failure = RemotePointerResult(
            streamId,
            2L,
            RemotePointerAction.CLICK,
            RemotePointerErrorCode.GESTURE_CANCELLED,
        )
        assertEquals(success, RemotePointerContract.parseResult(RemotePointerContract.result(success)))
        assertEquals(failure, RemotePointerContract.parseResult(RemotePointerContract.result(failure)))

        val inconsistent = RemotePointerContract.result(success).put("success", false)
        assertNull(RemotePointerContract.parseResult(inconsistent))
        assertNull(
            RemotePointerContract.parseResult(
                RemotePointerContract.result(failure).put("errorCode", "root"),
            ),
        )
    }

    @Test
    fun `unknown fields are tolerated only inside the bounded envelope`() {
        val command = RemotePointerContract.command(
            RemotePointerCommand(streamId, 1L, RemotePointerAction.CLICK, 0.5, 0.5),
        )
        assertEquals(
            RemotePointerCommand(streamId, 1L, RemotePointerAction.CLICK, 0.5, 0.5),
            RemotePointerContract.parseCommand(JSONObject(command.toString()).put("future", true)),
        )
        assertNull(
            RemotePointerContract.parseCommand(
                JSONObject(command.toString()).put(
                    "future",
                    "x".repeat(RemotePointerContract.MAX_MESSAGE_CHARS),
                ),
            ),
        )
    }
}
