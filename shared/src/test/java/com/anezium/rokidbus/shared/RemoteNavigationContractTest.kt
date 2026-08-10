package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNavigationContractTest {
    private val requestId = "navigation_0123456789abcdef"

    @Test
    fun `navigation uses core paths independent of remote input`() {
        assertEquals("/core/navigation/request", RemoteNavigationContract.REQUEST_PATH)
        assertEquals("/core/navigation/result", RemoteNavigationContract.RESULT_PATH)
        assertTrue(!RemoteNavigationContract.REQUEST_PATH.startsWith("/core/remote-input/"))
    }

    @Test
    fun `all system navigation actions round trip without an input session`() {
        RemoteNavigationAction.entries.forEach { action ->
            val request = RemoteNavigationRequest(requestId, action)

            assertEquals(
                request,
                RemoteNavigationContract.parseRequest(RemoteNavigationContract.request(request)),
            )
        }
    }

    @Test
    fun `successful and failed results round trip`() {
        val success = RemoteNavigationResult(requestId, RemoteNavigationAction.SELECT)
        val failure = RemoteNavigationResult(
            requestId,
            RemoteNavigationAction.BACK,
            RemoteNavigationErrorCode.SERVICE_UNAVAILABLE,
        )

        assertEquals(success, RemoteNavigationContract.parseResult(RemoteNavigationContract.result(success)))
        assertEquals(failure, RemoteNavigationContract.parseResult(RemoteNavigationContract.result(failure)))
    }

    @Test
    fun `malformed version id action and result consistency are rejected`() {
        val request = RemoteNavigationContract.request(
            RemoteNavigationRequest(requestId, RemoteNavigationAction.NEXT),
        )
        assertNull(RemoteNavigationContract.parseRequest(JSONObject(request.toString()).put("version", 2)))
        assertNull(RemoteNavigationContract.parseRequest(JSONObject(request.toString()).put("requestId", "short")))
        assertNull(RemoteNavigationContract.parseRequest(JSONObject(request.toString()).put("action", "shell")))

        val result = RemoteNavigationContract.result(
            RemoteNavigationResult(requestId, RemoteNavigationAction.NEXT),
        )
        assertNull(RemoteNavigationContract.parseResult(JSONObject(result.toString()).put("success", false)))
        assertNull(
            RemoteNavigationContract.parseResult(
                JSONObject(result.toString()).put("errorCode", "internal"),
            ),
        )
        assertNull(
            RemoteNavigationContract.parseResult(
                JSONObject(result.toString()).put("success", false).put("errorCode", "root"),
            ),
        )
    }

    @Test
    fun `unknown fields are tolerated within the control envelope limit`() {
        val request = RemoteNavigationContract.request(
            RemoteNavigationRequest(requestId, RemoteNavigationAction.PREVIOUS),
        )
        assertEquals(
            RemoteNavigationRequest(requestId, RemoteNavigationAction.PREVIOUS),
            RemoteNavigationContract.parseRequest(JSONObject(request.toString()).put("future", true)),
        )
        assertNull(
            RemoteNavigationContract.parseRequest(
                JSONObject(request.toString()).put(
                    "future",
                    "x".repeat(RemoteNavigationContract.MAX_MESSAGE_CHARS),
                ),
            ),
        )
    }
}
