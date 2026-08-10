package com.anezium.rokidbus.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppsPhoneContractTest {
    @Test
    fun `codec preserves valid app rows and actions`() {
        val source = listOf(
            NativeGlassesApp("youtube", "YouTube", "Installed", NativeAppAction.OPEN),
            NativeGlassesApp("maps", "Maps", "Available", NativeAppAction.INSTALL),
        )

        val decoded = NativeAppsCodec.decode(NativeAppsCodec.encode(source))

        assertEquals(NativeAppsUiState.Content(source), decoded)
    }

    @Test
    fun `empty invalid and malformed catalogues produce explicit UI states`() {
        val onlyInvalidRows = JSONArray()
            .put(JSONObject().put("id", "").put("name", "Missing id"))
            .put(JSONObject().put("id", "missing-name"))
            .toString()

        assertEquals(NativeAppsUiState.Empty, NativeAppsCodec.decode(null))
        assertEquals(NativeAppsUiState.Empty, NativeAppsCodec.decode(onlyInvalidRows))
        assertTrue(NativeAppsCodec.decode("not-json") is NativeAppsUiState.Error)
    }

    @Test
    fun `unknown action is rendered read only instead of becoming an install action`() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "future")
                .put("name", "Future app")
                .put("detail", "Unknown state")
                .put("action", "erase_everything"),
        ).toString()

        val state = NativeAppsCodec.decode(raw) as NativeAppsUiState.Content

        assertEquals(NativeAppAction.NONE, state.apps.single().action)
    }
}
