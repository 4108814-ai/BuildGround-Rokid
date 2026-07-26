package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSurfaceContractTest {
    @Test
    fun `trims text collapses newlines and defaults ttl`() {
        val result = NoticeSurfaceContract.validateShow(
            JSONObject()
                .put("kind", "notice")
                .put("title", "  Marie  ")
                .put("body", "On my way,\nten minutes out.")
                .put("footer", " tap to reply "),
        )

        val content = (result as NoticeSurfaceValidationResult.Valid).content
        assertEquals("Marie", content.title)
        assertEquals("On my way, ten minutes out.", content.body)
        assertEquals("tap to reply", content.footer)
        assertFalse(content.interactive)
        assertEquals(NoticeSurfaceContract.DEFAULT_TTL_MS, content.ttlMs)
    }

    @Test
    fun `clamps ttl to the notice window`() {
        val floor = NoticeSurfaceContract.validateShow(showPayload().put("ttlMs", 10L))
        val ceiling = NoticeSurfaceContract.validateShow(showPayload().put("ttlMs", 600_000L))

        assertEquals(
            NoticeSurfaceContract.MIN_TTL_MS,
            (floor as NoticeSurfaceValidationResult.Valid).content.ttlMs,
        )
        assertEquals(
            NoticeSurfaceContract.MAX_TTL_MS,
            (ceiling as NoticeSurfaceValidationResult.Valid).content.ttlMs,
        )
    }

    @Test
    fun `rejects a notice with no text`() {
        val result = NoticeSurfaceContract.validateShow(
            JSONObject().put("kind", "notice").put("footer", "tap to reply"),
        )

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects text past its cap rather than truncating`() {
        val result = NoticeSurfaceContract.validateShow(
            showPayload().put("title", "x".repeat(NoticeSurfaceContract.MAX_TITLE_CHARS + 1)),
        )

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects a wrong kind and a non-boolean interactive`() {
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("kind", "pin"))
                is NoticeSurfaceValidationResult.Invalid,
        )
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("interactive", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `an update leaves absent fields alone`() {
        val current = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = "tap to reply",
            interactive = true,
            ttlMs = 8_000L,
        )

        val patch = NoticeSurfaceContract.validateUpdate(JSONObject().put("footer", "Listening…"))
        val updated = (patch as NoticeSurfacePatchResult.Valid).patch.applyTo(current)

        assertEquals("Marie", updated.title)
        assertEquals("On my way", updated.body)
        assertEquals("Listening…", updated.footer)
        assertTrue(updated.interactive)
        assertEquals(8_000L, updated.ttlMs)
    }

    @Test
    fun `an update can clear a field it sends empty`() {
        val current = NoticeSurfaceContent("Marie", "On my way", "tap to reply")

        val patch = NoticeSurfaceContract.validateUpdate(JSONObject().put("footer", "   "))
        val updated = (patch as NoticeSurfacePatchResult.Valid).patch.applyTo(current)

        assertNull(updated.footer)
        assertEquals("Marie", updated.title)
    }

    @Test
    fun `an update still enforces the caps`() {
        val patch = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("body", "x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS + 1)),
        )

        assertTrue(patch is NoticeSurfacePatchResult.Invalid)
    }

    @Test
    fun `payload omits interactive when false and round-trips`() {
        val content = NoticeSurfaceContent("Marie", "On my way", null, interactive = false)
        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)

        assertFalse(payload.has("interactive"))
        assertFalse(payload.has("footer"))
        assertEquals("relay:notice", payload.optString("surfaceId"))

        val reparsed = NoticeSurfaceContract.validateShow(payload)
        assertEquals(content.copy(ttlMs = content.ttlMs), (reparsed as NoticeSurfaceValidationResult.Valid).content)
    }

    @Test
    fun `closed payload carries the reason`() {
        val payload = NoticeSurfaceContract.closedPayload("relay:notice", NoticeCloseReason.TIMEOUT)

        assertEquals("relay:notice", payload.optString("noticeId"))
        assertEquals("timeout", payload.optString("reason"))
        assertEquals(NoticeCloseReason.TIMEOUT, NoticeCloseReason.fromWireValue("timeout"))
    }

    private fun showPayload() = JSONObject()
        .put("kind", "notice")
        .put("title", "Marie")
        .put("body", "On my way")
}
