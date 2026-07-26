package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.PinSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePinStateTest {
    private var now = 10_000L
    private val state = PhonePinState(nowMs = { now }, initialSequence = 40L)

    @Test
    fun `validates caps before consuming the per-plugin rate limit`() {
        val invalid = owned("alpha").put("title", "x".repeat(25))
        assertRejected(invalid, "alpha", PinSurfaceContract.ERROR_INVALID_PIN)
        assertRejected(
            owned("alpha").put("ownerPluginId", "beta"),
            "alpha",
            PinSurfaceContract.ERROR_INVALID_PIN,
        )
        assertRejected(
            owned("alpha").put("surfaceId", "alpha:other"),
            "alpha",
            PinSurfaceContract.ERROR_INVALID_PIN,
        )

        val accepted = state.show("alpha", owned("alpha")) as PhonePinShowResult.Accepted
        assertEquals(41L, accepted.pin.payload.getLong("seq"))
        assertRejected(owned("alpha"), "alpha", PinSurfaceContract.ERROR_PIN_RATE_LIMITED)

        now += PinSurfaceContract.MIN_SHOW_INTERVAL_MS
        assertTrue(state.show("alpha", owned("alpha")) is PhonePinShowResult.Accepted)
    }

    @Test
    fun `last writer wins and only the current owner can hide`() {
        state.show("alpha", owned("alpha"))
        now += 1L
        val replacement = state.show("beta", owned("beta")) as PhonePinShowResult.Accepted
        assertEquals("alpha", replacement.replacedOwnerPluginId)

        assertTrue(state.hide("alpha") is PhonePinClearResult.Ignored)
        val cleared = state.hide("beta") as PhonePinClearResult.Cleared
        assertEquals("beta:pin", cleared.payload.getString("surfaceId"))
        assertEquals(43L, cleared.payload.getLong("seq"))
        assertNull(state.payloadForResend())
    }

    @Test
    fun `ttl expiry clears canonical state with a newer synthetic hide`() {
        val accepted = state.show(
            "alpha",
            owned("alpha").put("ttlMs", PinSurfaceContract.MIN_TTL_MS),
        ) as PhonePinShowResult.Accepted
        assertEquals(now + PinSurfaceContract.MIN_TTL_MS, accepted.pin.expiresAtMs)

        now += PinSurfaceContract.MIN_TTL_MS - 1L
        assertTrue(state.expireIfDue() is PhonePinClearResult.Ignored)
        now += 1L
        val expired = state.expireIfDue() as PhonePinClearResult.Cleared
        assertEquals(42L, expired.payload.getLong("seq"))
        assertNull(state.payloadForResend())
    }

    @Test
    fun `losing access clears only the owning plugin`() {
        state.show("alpha", owned("alpha"))
        assertTrue(state.ownerLostAccess("beta") is PhonePinClearResult.Ignored)
        assertTrue(state.ownerLostAccess("alpha") is PhonePinClearResult.Cleared)
    }

    @Test
    fun `owner id is exposed so a revoked dormant plugin can be matched`() {
        assertNull(state.ownerPluginId())
        state.show("alpha", owned("alpha"))
        assertEquals("alpha", state.ownerPluginId())
        state.ownerLostAccess("alpha")
        assertNull(state.ownerPluginId())
    }

    @Test
    fun `empty slot hide payload asserts cleared state with a fresh sequence`() {
        val syncHide = state.emptySlotHidePayload()
        assertEquals("nexus-hub:pin", syncHide?.getString("surfaceId"))
        state.show("alpha", owned("alpha"))
        assertNull(state.emptySlotHidePayload())
        val cleared = state.ownerLostAccess("alpha") as PhonePinClearResult.Cleared
        val afterClear = state.emptySlotHidePayload()
        assertNotNull(afterClear)
        assertTrue(afterClear!!.getLong("seq") > cleared.payload.getLong("seq"))
    }

    @Test
    fun `size and line emphasis survive the normalized payload rebuild`() {
        assertRejected(
            owned("alpha").put("lines", JSONArray().put("a").put("b").put("c")),
            "alpha",
            PinSurfaceContract.ERROR_INVALID_PIN,
        )

        val accepted = state.show("alpha", medium("alpha")) as PhonePinShowResult.Accepted
        val normalized = accepted.pin.payload
        assertEquals("medium", normalized.getString("size"))
        val lines = normalized.getJSONArray("lines")
        assertEquals("arrives in 4 min", lines.getJSONObject(0).getString("text"))
        assertEquals("bright", lines.getJSONObject(0).getString("emphasis"))
        assertEquals("route 42", lines.getString(1))
        assertEquals("dim", lines.getJSONObject(2).getString("emphasis"))

        val resend = state.payloadForResend()
        assertEquals("medium", resend?.getString("size"))
        assertEquals("bright", resend?.getJSONArray("lines")?.getJSONObject(0)?.getString("emphasis"))
    }

    private fun medium(pluginId: String) = owned(pluginId)
        .put("size", "medium")
        .put(
            "lines",
            JSONArray()
                .put(JSONObject().put("text", " arrives in 4 min ").put("emphasis", "bright"))
                .put("route 42")
                .put(JSONObject().put("text", "platform 2").put("emphasis", "dim")),
        )

    private fun owned(pluginId: String) = JSONObject()
        .put("surfaceId", "$pluginId:pin")
        .put("localSurfaceId", "pin")
        .put("ownerPluginId", pluginId)
        .put("kind", "pin")
        .put("title", "NEXUS PIN")
        .put("lines", JSONArray().put("sample overlay"))

    private fun assertRejected(payload: JSONObject, owner: String, code: String) {
        val result = state.show(owner, payload)
        assertTrue(result is PhonePinShowResult.Rejected)
        assertEquals(code, (result as PhonePinShowResult.Rejected).code)
    }
}
