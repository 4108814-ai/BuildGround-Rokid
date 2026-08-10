package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppContractTest {
    private val requestId = "request_0123456789abcdef"

    @Test
    fun `native app paths are core-only bus routes`() {
        assertEquals("/core/native-apps/request", NativeAppContract.REQUEST_PATH)
        assertEquals("/core/native-apps/result", NativeAppContract.RESULT_PATH)
    }

    @Test
    fun `list request round trips and tolerates future fields`() {
        val payload = NativeAppContract.listRequest(requestId).put("future", true)

        assertEquals(requestId, NativeAppContract.parseListRequest(payload))
    }

    @Test
    fun `successful list result preserves ordered launchable app metadata`() {
        val result = NativeAppListResult(
            requestId,
            listOf(
                NativeAppEntry("app.morphe.android.youtube", "YouTube", 21_040_223L),
                NativeAppEntry("com.example.player", "Léger 👓", null),
            ),
        )

        val decoded = NativeAppContract.parseListResult(NativeAppContract.listResult(result))

        assertEquals(result, decoded)
        assertTrue(decoded!!.success)
    }

    @Test
    fun `failed list result round trips without app entries`() {
        val result = NativeAppListResult(
            requestId,
            emptyList(),
            NativeAppErrorCode.INTERNAL,
        )

        assertEquals(result, NativeAppContract.parseListResult(NativeAppContract.listResult(result)))
        assertFalse(result.success)
    }

    @Test
    fun `list rejects duplicates malformed labels and impossible versions`() {
        val valid = NativeAppContract.listResult(
            NativeAppListResult(requestId, listOf(NativeAppEntry("com.example.app", "Example", 1))),
        )
        val apps = valid.getJSONArray("apps")
        apps.put(JSONObject(apps.getJSONObject(0).toString()))
        assertNull(NativeAppContract.parseListResult(valid))

        listOf("", " padded ", "line\nbreak", "\uD83D").forEach { label ->
            val payload = NativeAppContract.listResult(
                NativeAppListResult(requestId, listOf(NativeAppEntry("com.example.app", "Valid", 1))),
            )
            payload.getJSONArray("apps").getJSONObject(0).put("label", label)
            assertNull(NativeAppContract.parseListResult(payload))
        }

        val negativeVersion = NativeAppContract.listResult(
            NativeAppListResult(requestId, listOf(NativeAppEntry("com.example.app", "Valid", 1))),
        )
        negativeVersion.getJSONArray("apps").getJSONObject(0).put("versionCode", -1)
        assertNull(NativeAppContract.parseListResult(negativeVersion))
    }

    @Test
    fun `list count and aggregate payload size are bounded`() {
        val tooMany = List(NativeAppContract.MAX_APPS + 1) { index ->
            NativeAppEntry("com.example.app$index", "App $index")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAppContract.listResult(NativeAppListResult(requestId, tooMany))
        }

        val valid = NativeAppContract.listResult(
            NativeAppListResult(requestId, listOf(NativeAppEntry("com.example.app", "Valid"))),
        )
        assertNull(
            NativeAppContract.parseListResult(
                JSONObject(valid.toString()).put(
                    "future",
                    "x".repeat(NativeAppContract.MAX_MESSAGE_CHARS),
                ),
            ),
        )

        val multibyteCatalog = List(NativeAppContract.MAX_APPS) { index ->
            NativeAppEntry("com.example.app$index", "界".repeat(NativeAppContract.MAX_LABEL_LENGTH))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAppContract.listResult(NativeAppListResult(requestId, multibyteCatalog))
        }
    }

    @Test
    fun `failed catalogs cannot smuggle app entries`() {
        val payload = NativeAppContract.listResult(
            NativeAppListResult(requestId, emptyList(), NativeAppErrorCode.INTERNAL),
        )
        payload.getJSONArray("apps").put(
            JSONObject().put("packageName", "com.example.app").put("label", "Example"),
        )

        assertNull(NativeAppContract.parseListResult(payload))
    }

    @Test
    fun `launch request and success and failure results round trip`() {
        val request = NativeAppLaunchRequest(requestId, "app.morphe.android.youtube")
        val success = NativeAppLaunchResult(requestId, request.packageName)
        val failure = NativeAppLaunchResult(
            requestId,
            request.packageName,
            NativeAppErrorCode.NOT_LAUNCHABLE,
        )

        assertEquals(request, NativeAppContract.parseLaunchRequest(NativeAppContract.launchRequest(request)))
        assertEquals(success, NativeAppContract.parseLaunchResult(NativeAppContract.launchResult(success)))
        assertEquals(failure, NativeAppContract.parseLaunchResult(NativeAppContract.launchResult(failure)))
    }

    @Test
    fun `launch parser rejects injection malformed success and unknown errors`() {
        val request = NativeAppContract.launchRequest(
            NativeAppLaunchRequest(requestId, "com.example.app"),
        )
        assertNull(
            NativeAppContract.parseLaunchRequest(
                JSONObject(request.toString()).put("packageName", "com.example.app/.Main;sh"),
            ),
        )

        val result = NativeAppContract.launchResult(
            NativeAppLaunchResult(requestId, "com.example.app"),
        )
        assertNull(NativeAppContract.parseLaunchResult(JSONObject(result.toString()).put("success", "true")))
        assertNull(
            NativeAppContract.parseLaunchResult(
                JSONObject(result.toString()).put("success", false).put("errorCode", "root_shell"),
            ),
        )
        assertNull(
            NativeAppContract.parseLaunchResult(
                JSONObject(result.toString()).put("success", false),
            ),
        )
    }

    @Test
    fun `all messages require strict version type and request id`() {
        val request = NativeAppContract.listRequest(requestId)

        assertNull(NativeAppContract.parseListRequest(JSONObject(request.toString()).put("version", 2)))
        assertNull(NativeAppContract.parseListRequest(JSONObject(request.toString()).put("version", 1.0)))
        assertNull(NativeAppContract.parseListRequest(JSONObject(request.toString()).put("type", "launch_all")))
        assertNull(NativeAppContract.parseListRequest(JSONObject(request.toString()).put("requestId", "short")))
    }

    @Test
    fun `package and request identifiers use conservative allowlists`() {
        assertTrue(NativeAppContract.isValidPackageName("app.morphe.android.youtube"))
        assertFalse(NativeAppContract.isValidPackageName("single"))
        assertFalse(NativeAppContract.isValidPackageName("9com.example"))
        assertFalse(NativeAppContract.isValidPackageName("com.example-app"))
        assertFalse(NativeAppContract.isValidPackageName("com.example/.Main"))
        assertTrue(NativeAppContract.isValidRequestId(requestId))
        assertFalse(NativeAppContract.isValidRequestId("../request"))
    }

    @Test
    fun `array fields must be actual arrays`() {
        val result = NativeAppContract.listResult(NativeAppListResult(requestId, emptyList()))

        assertNull(NativeAppContract.parseListResult(JSONObject(result.toString()).put("apps", JSONObject())))
        assertNull(
            NativeAppContract.parseListResult(
                JSONObject(result.toString()).put("apps", JSONArray().put("not-an-object")),
            ),
        )
    }
}
