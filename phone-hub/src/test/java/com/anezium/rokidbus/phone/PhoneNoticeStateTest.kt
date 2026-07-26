package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeStateTest {

    private var now = 0L
    private val state = PhoneNoticeState(nowMs = { now }, initialSequence = 0L)

    @Test
    fun `accepts a well-formed show and stamps a sequence`() {
        val result = state.show("relay", showPayload("relay"))

        val notice = (result as PhoneNoticeShowResult.Accepted).notice
        assertEquals("relay", notice.ownerPluginId)
        assertEquals(1L, notice.payload.optLong("seq"))
        assertNull(result.replacedOwnerPluginId)
    }

    @Test
    fun `rejects a payload whose owner does not match the sender`() {
        val result = state.show("relay", showPayload("maps"))

        assertEquals(
            NoticeSurfaceContract.ERROR_INVALID_NOTICE,
            (result as PhoneNoticeShowResult.Rejected).code,
        )
    }

    @Test
    fun `reports the plugin whose notice was replaced`() {
        state.show("relay", showPayload("relay"))
        now += 500L

        val result = state.show("maps", showPayload("maps"))

        assertEquals("relay", (result as PhoneNoticeShowResult.Accepted).replacedOwnerPluginId)
    }

    @Test
    fun `show and update share one rate budget`() {
        repeat(NoticeSurfaceContract.MAX_MESSAGES_PER_SECOND) {
            assertTrue(state.show("relay", showPayload("relay")) is PhoneNoticeShowResult.Accepted)
        }

        val blockedUpdate = state.update("relay", JSONObject().put("footer", "Listening"))
        assertEquals(
            NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED,
            (blockedUpdate as PhoneNoticeUpdateResult.Rejected).code,
        )

        // The window slides rather than resetting on a fixed tick.
        now += 1_000L
        assertTrue(state.update("relay", JSONObject().put("footer", "Listening")) is PhoneNoticeUpdateResult.Accepted)
    }

    @Test
    fun `an update restarts the ttl but cannot outlive the hard deadline`() {
        state.show("relay", showPayload("relay").put("ttlMs", 20_000L))
        val hardDeadline = NoticeSurfaceContract.MAX_LIFETIME_MS

        // Keep updating well past the point where restarting a 20s TTL would
        // otherwise keep the banner up forever.
        var updates = 0
        while (now < hardDeadline) {
            now += 5_000L
            state.update("relay", JSONObject().put("footer", "still here $updates"))
            updates++
        }

        assertEquals(hardDeadline, state.expiryDeadlineMs())
        val cleared = state.expireIfDue()
        assertEquals(NoticeCloseReason.TIMEOUT, (cleared as PhoneNoticeClearResult.Cleared).reason)
    }

    @Test
    fun `an update from a plugin that does not own the slot is ignored`() {
        state.show("relay", showPayload("relay"))

        val result = state.update("maps", JSONObject().put("title", "Hijacked"))

        assertTrue(result is PhoneNoticeUpdateResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    @Test
    fun `a glasses-side close for the wrong surface is ignored`() {
        state.show("relay", showPayload("relay"))

        val wrong = state.closedByGlasses("maps:notice", NoticeCloseReason.USER)
        assertTrue(wrong is PhoneNoticeClearResult.Ignored)

        val right = state.closedByGlasses("relay:notice", NoticeCloseReason.USER)
        assertEquals(NoticeCloseReason.USER, (right as PhoneNoticeClearResult.Cleared).reason)
        assertNull(state.ownerPluginId())
    }

    @Test
    fun `losing access closes the notice with the disconnect reason`() {
        state.show("relay", showPayload("relay"))

        val cleared = state.ownerLostAccess("relay")

        assertEquals(NoticeCloseReason.DISCONNECT, (cleared as PhoneNoticeClearResult.Cleared).reason)
        assertEquals("relay", cleared.ownerPluginId)
    }

    @Test
    fun `hide from a plugin that is not the owner changes nothing`() {
        state.show("relay", showPayload("relay"))

        assertTrue(state.hide("maps") is PhoneNoticeClearResult.Ignored)
        assertEquals("relay", state.ownerPluginId())
    }

    private fun showPayload(ownerPluginId: String) = JSONObject()
        .put("surfaceId", "$ownerPluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("kind", NoticeSurfaceContract.KIND)
        .put("title", "Marie")
        .put("body", "On my way")
}
