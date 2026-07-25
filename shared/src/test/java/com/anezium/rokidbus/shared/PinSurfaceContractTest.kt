package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf(PinSurfaceLine("Grey Prius")), content.lines)
        assertEquals(PinSurfacePosition.TOP_RIGHT, content.position)
        assertEquals(PinSurfaceSize.SMALL, content.size)
        assertEquals(PinSurfaceContract.MIN_TTL_MS, content.ttlMs)

        val payload = PinSurfaceContract.toPayload("rides:pin", content)
        assertFalse(payload.has("size"))
        assertEquals("Grey Prius", payload.getJSONArray("lines").getString(0))
    }

    @Test
    fun `accepts the medium tier and per line emphasis`() {
        val result = PinSurfaceContract.validateShow(
            JSONObject()
                .put("kind", "pin")
                .put("size", "medium")
                .put("title", "x".repeat(PinSurfaceSize.MEDIUM.maxTitleChars))
                .put(
                    "lines",
                    JSONArray()
                        .put(JSONObject().put("text", "  arrives in 4 min  ").put("emphasis", "bright"))
                        .put("x".repeat(PinSurfaceSize.MEDIUM.maxLineChars))
                        .put(JSONObject().put("text", "platform 2").put("emphasis", "dim")),
                ),
        )

        assertTrue(result is PinSurfaceValidationResult.Valid)
        val content = (result as PinSurfaceValidationResult.Valid).content
        assertEquals(PinSurfaceSize.MEDIUM, content.size)
        assertEquals(
            listOf(
                PinSurfaceLine("arrives in 4 min", PinSurfaceEmphasis.BRIGHT),
                PinSurfaceLine("x".repeat(PinSurfaceSize.MEDIUM.maxLineChars)),
                PinSurfaceLine("platform 2", PinSurfaceEmphasis.DIM),
            ),
            content.lines,
        )

        val payload = PinSurfaceContract.toPayload("transit:pin", content)
        assertEquals("medium", payload.getString("size"))
        val lines = payload.getJSONArray("lines")
        assertEquals("bright", lines.getJSONObject(0).getString("emphasis"))
        assertEquals("arrives in 4 min", lines.getJSONObject(0).getString("text"))
        assertTrue(lines.opt(1) is String)
        assertEquals("dim", lines.getJSONObject(2).getString("emphasis"))
        assertEquals(content, (PinSurfaceContract.validateShow(payload) as PinSurfaceValidationResult.Valid).content)
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

    @Test
    fun `rejects medium caps unknown sizes and malformed emphasis`() {
        val invalid = listOf(
            JSONObject().put("kind", "pin").put("size", "medium").put("title", "x".repeat(29)),
            JSONObject().put("kind", "pin").put("size", "medium")
                .put("lines", JSONArray().put("x".repeat(33))),
            JSONObject().put("kind", "pin").put("size", "medium")
                .put("lines", JSONArray().put("a").put("b").put("c").put("d")),
            // Medium caps must not leak into the small tier.
            JSONObject().put("kind", "pin").put("lines", JSONArray().put("x".repeat(32))),
            JSONObject().put("kind", "pin").put("size", "large").put("title", "x"),
            JSONObject().put("kind", "pin").put("size", 2).put("title", "x"),
            JSONObject().put("kind", "pin")
                .put("lines", JSONArray().put(JSONObject().put("text", "x").put("emphasis", "loud"))),
            JSONObject().put("kind", "pin")
                .put("lines", JSONArray().put(JSONObject().put("text", "x").put("emphasis", 1))),
            JSONObject().put("kind", "pin")
                .put("lines", JSONArray().put(JSONObject().put("emphasis", "bright"))),
            JSONObject().put("kind", "pin").put("lines", JSONArray().put(7)),
        )

        invalid.forEach { payload ->
            assertTrue(payload.toString(), PinSurfaceContract.validateShow(payload) is PinSurfaceValidationResult.Invalid)
        }
    }
}
