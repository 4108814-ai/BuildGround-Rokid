package com.anezium.rokidbus.shared

import org.json.JSONArray
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

    /**
     * The compatibility pin for the whole feature. Asserted key by key rather
     * than against a serialised string: `JSONObject` is backed by a HashMap
     * here, so a string comparison would fail on key order for reasons that
     * have nothing to do with what is on the wire, and the receiver reads by
     * key anyway.
     */
    @Test
    fun `a notice with no actions puts nothing new on the wire`() {
        val content = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = "tap to reply",
            interactive = true,
        )

        val payload = NoticeSurfaceContract.toPayload("relay:notice", content)

        assertEquals(
            setOf("surfaceId", "kind", "ttlMs", "title", "body", "footer", "interactive"),
            payload.keys().asSequence().toSet(),
        )
        assertFalse(payload.has("actions"))
        assertEquals("relay:notice", payload.getString("surfaceId"))
        assertEquals("notice", payload.getString("kind"))
        assertEquals(NoticeSurfaceContract.DEFAULT_TTL_MS, payload.getLong("ttlMs"))
        assertEquals("Marie", payload.getString("title"))
        assertEquals("On my way", payload.getString("body"))
        assertEquals("tap to reply", payload.getString("footer"))
        assertTrue(payload.getBoolean("interactive"))
    }

    @Test
    fun `actions round-trip trimmed, in order, with their glyphs`() {
        val payload = showPayload().put(
            "actions",
            JSONArray()
                .put(action("  yes  ", "  play  ", "  Accept  "))
                .put(action("no", "stop", "Decline")),
        )

        val content = (NoticeSurfaceContract.validateShow(payload)
            as NoticeSurfaceValidationResult.Valid).content
        assertEquals(
            listOf(
                NoticeAction("yes", "play", "Accept"),
                NoticeAction("no", "stop", "Decline"),
            ),
            content.actions,
        )

        val reserialized = NoticeSurfaceContract
            .toPayload("relay:notice", content)
            .getJSONArray("actions")
        assertEquals(2, reserialized.length())
        assertEquals("yes", reserialized.getJSONObject(0).getString("id"))
        assertEquals("play", reserialized.getJSONObject(0).getString("glyph"))
        assertEquals("Accept", reserialized.getJSONObject(0).getString("label"))
        assertEquals("no", reserialized.getJSONObject(1).getString("id"))
    }

    @Test
    fun `rejects a fourth action rather than dropping it`() {
        val actions = JSONArray()
        repeat(NoticeSurfaceContract.MAX_ACTIONS + 1) { index ->
            actions.put(action("id-$index", "play", "Label"))
        }

        val result = NoticeSurfaceContract.validateShow(showPayload().put("actions", actions))

        assertTrue(result is NoticeSurfaceValidationResult.Invalid)
    }

    @Test
    fun `rejects malformed actions field by field`() {
        val malformed = listOf(
            JSONArray().put("yes"),
            JSONArray().put(action("", "play", "Accept")),
            JSONArray().put(action("yes", "not a glyph", "Accept")),
            JSONArray().put(action("yes", "play", "   ")),
        )

        malformed.forEach { actions ->
            assertTrue(
                "expected $actions to be refused",
                NoticeSurfaceContract.validateShow(showPayload().put("actions", actions))
                    is NoticeSurfaceValidationResult.Invalid,
            )
        }
        assertTrue(
            NoticeSurfaceContract.validateShow(showPayload().put("actions", "yes"))
                is NoticeSurfaceValidationResult.Invalid,
        )
    }

    @Test
    fun `an update replaces the row only when it carries one`() {
        val current = NoticeSurfaceContent(
            title = "Marie",
            body = "On my way",
            footer = null,
            actions = listOf(NoticeAction("yes", "play", "Accept")),
        )

        val untouched = NoticeSurfaceContract.validateUpdate(JSONObject().put("body", "Five out"))
        assertEquals(
            current.actions,
            (untouched as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions,
        )

        val replaced = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("actions", JSONArray().put(action("no", "stop", "Decline"))),
        )
        assertEquals(
            listOf(NoticeAction("no", "stop", "Decline")),
            (replaced as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions,
        )

        val cleared = NoticeSurfaceContract.validateUpdate(
            JSONObject().put("actions", JSONArray()),
        )
        assertTrue(
            (cleared as NoticeSurfacePatchResult.Valid).patch.applyTo(current).actions.isEmpty(),
        )
    }

    @Test
    fun `offering actions is asking for input, with or without the flag`() {
        val plain = NoticeSurfaceContent("Marie", "On my way", null)
        val flagged = plain.copy(interactive = true)
        val chosen = plain.copy(actions = listOf(NoticeAction("yes", "play", "Accept")))

        assertFalse(plain.expectsInput)
        assertTrue(flagged.expectsInput)
        assertTrue(chosen.expectsInput)
    }

    @Test
    fun `action payload names the notice and the action`() {
        val payload = NoticeSurfaceContract.actionPayload("relay:notice", "yes")

        assertEquals("relay:notice", payload.getString("noticeId"))
        assertEquals("yes", payload.getString("id"))
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

    private fun action(id: String, glyph: String, label: String) = JSONObject()
        .put("id", id)
        .put("glyph", glyph)
        .put("label", label)
}
