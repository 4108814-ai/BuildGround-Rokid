package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSurfaceContractTest {
    @Test
    fun `normalizes text defaults position and clamps ttl`() {
        val result = PinSurfaceContract.validateShow(
            JSONObject()
                .put("kind", "pin")
                .put("title", "  AB-123-CD  ")
                .put("lines", JSONArray().put(" Grey Prius "))
                .put("ttlMs", 50L),
        )

        assertTrue(result is PinSurfaceValidationResult.Valid)
        val content = (result as PinSurfaceValidationResult.Valid).content
        assertEquals("AB-123-CD", content.title)
        assertEquals(listOf("Grey Prius"), content.lines)
        assertEquals(PinSurfacePosition.TOP_RIGHT, content.position)
        assertEquals(PinSurfaceContract.MIN_TTL_MS, content.ttlMs)
    }

    @Test
    fun `rejects caps shapes and empty content`() {
        val invalid = listOf(
            JSONObject().put("kind", "pin").put("title", "x".repeat(25)),
            JSONObject().put("kind", "pin").put("lines", JSONArray().put("x".repeat(29))),
            JSONObject().put("kind", "pin").put("lines", JSONArray().put("a").put("b").put("c")),
            JSONObject().put("kind", "pin").put("title", "  ").put("lines", JSONArray().put(" ")),
            JSONObject().put("kind", "pin").put("position", "center").put("title", "x"),
            JSONObject().put("kind", "pin").put("ttlMs", 1.5).put("title", "x"),
        )

        invalid.forEach { payload ->
            assertTrue(payload.toString(), PinSurfaceContract.validateShow(payload) is PinSurfaceValidationResult.Invalid)
        }
    }
}
