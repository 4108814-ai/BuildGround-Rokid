package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {
    @Test
    fun `prefix matching respects segment boundaries`() {
        assertTrue(PathRules.matchesPrefix("/plugin/hello", "/plugin/hello"))
        assertTrue(PathRules.matchesPrefix("/plugin/hello/reply", "/plugin/hello"))
        assertFalse(PathRules.matchesPrefix("/plugin/hello-world", "/plugin/hello"))
        assertFalse(PathRules.matchesPrefix("/foobar", "/foo"))
    }

    @Test
    fun `invalid and root paths are rejected`() {
        assertNull(PathRules.normalizeAbsolute(""))
        assertNull(PathRules.normalizeAbsolute("relative"))
        assertNull(PathRules.normalizeAbsolute("/"))
        assertNull(PathRules.normalizeAbsolute("/a//b"))
        assertNull(PathRules.normalizeAbsolute("/a/../b"))
        assertEquals("/a/b", PathRules.normalizeAbsolute("  /a/b  "))
    }

    @Test
    fun `reserved routes use segment-aware ownership`() {
        assertTrue(PathRules.isReserved("/launcher"))
        assertTrue(PathRules.isReserved("/launcher/open"))
        assertTrue(PathRules.isReserved("/surface/input"))
        assertTrue(PathRules.isReserved("/system/plugin/open"))
        assertTrue(PathRules.isReserved("/security/revoke"))
        assertTrue(PathRules.isReserved("/error"))
        assertTrue(PathRules.isHubOnly("/tts/cancel"))
        assertTrue(PathRules.isReserved("/tts/cancel"))
        assertFalse(PathRules.isReserved("/launcherish"))
    }

    @Test
    fun `camera receive routes are capability conditioned and narrow`() {
        val camera = setOf(PluginCapability.CAMERA)
        assertTrue(PathRules.isAllowedReceivePrefix("/camera/session/state", "lens", camera))
        assertTrue(PathRules.isAllowedReceivePrefix("/camera/link/offer", "lens", camera))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera/session/state", "lens", emptySet()))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera", "lens", camera))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera/overlay", "lens", camera))
        assertEquals(
            PluginCapability.CAMERA,
            PathRules.requiredCapabilityForReceivePrefix("/camera/link/offer"),
        )
        assertEquals(PluginCapability.CAMERA, PathRules.requiredCapability("/camera/link/offer"))
        assertEquals(
            PluginCapability.CAMERA,
            PathRules.requiredCapability(BusPaths.CAMERA_SNAPSHOT_REQUEST),
        )
        assertTrue(
            PathRules.isAllowedReceivePrefix(
                BusPaths.CAMERA_SNAPSHOT_RESULT,
                "assistant",
                camera,
            ),
        )
        assertTrue(PathRules.isDirectReply(BusPaths.CAMERA_SNAPSHOT_RESULT))
        assertTrue(PathRules.isOwnerScoped(BusPaths.CAMERA_SNAPSHOT_ERROR))
        assertNull(PathRules.requiredCapability("/camera/session/state"))
    }

    @Test
    fun `media sync exposes status to receive and settings plus trigger to send`() {
        val mediaSync = setOf(PluginCapability.MEDIA_SYNC)
        assertTrue(PathRules.isAllowedReceivePrefix("/mediasync/status", "photosync", mediaSync))
        assertFalse(PathRules.isAllowedReceivePrefix("/mediasync/status", "photosync", emptySet()))
        assertFalse(PathRules.isAllowedReceivePrefix("/mediasync", "photosync", mediaSync))
        assertFalse(PathRules.isAllowedReceivePrefix("/mediasync/config", "photosync", mediaSync))
        assertEquals(
            PluginCapability.MEDIA_SYNC,
            PathRules.requiredCapabilityForReceivePrefix("/mediasync/status"),
        )
        assertEquals(PluginCapability.MEDIA_SYNC, PathRules.requiredCapability("/mediasync/settings"))
        assertEquals(PluginCapability.MEDIA_SYNC, PathRules.requiredCapability("/mediasync/now"))
        assertNull(PathRules.requiredCapability("/mediasync/link/offer"))
    }

    @Test
    fun `stt routes and receive namespace are capability conditioned`() {
        val stt = setOf(PluginCapability.STT)
        assertEquals(PluginCapability.STT, PathRules.requiredCapability("/stt/session/start"))
        assertEquals(PluginCapability.STT, PathRules.requiredCapability("/stt/session/stop"))
        assertTrue(PathRules.isAllowedReceivePrefix("/stt", "scribe", stt))
        assertTrue(PathRules.isAllowedReceivePrefix("/stt/partial", "scribe", stt))
        assertFalse(PathRules.isAllowedReceivePrefix("/stt", "scribe", emptySet()))
        assertEquals(PluginCapability.STT, PathRules.requiredCapabilityForReceivePrefix("/stt"))
        assertEquals(
            PluginCapability.STT,
            PathRules.requiredCapabilityForReceivePrefix("/stt/session/ended"),
        )
    }

    @Test
    fun `tts routes replies and receive namespace are capability conditioned`() {
        val tts = setOf(PluginCapability.TTS)
        assertEquals(PluginCapability.TTS, PathRules.requiredCapability("/tts/speak"))
        assertEquals(PluginCapability.TTS, PathRules.requiredCapability("/tts/stop"))
        assertNull(PathRules.requiredCapability("/tts/cancel"))
        assertTrue(PathRules.isAllowedReceivePrefix("/tts/started", "speaker", tts))
        assertTrue(PathRules.isAllowedReceivePrefix("/tts/done", "speaker", tts))
        assertFalse(PathRules.isAllowedReceivePrefix("/tts", "speaker", tts))
        assertFalse(PathRules.isAllowedReceivePrefix("/tts/cancel", "speaker", tts))
        assertFalse(PathRules.isAllowedReceivePrefix("/tts", "speaker", emptySet()))
        assertEquals(
            PluginCapability.TTS,
            PathRules.requiredCapabilityForReceivePrefix("/tts/started"),
        )
        assertNull(PathRules.requiredCapabilityForReceivePrefix("/tts"))
        assertNull(PathRules.requiredCapabilityForReceivePrefix("/tts/cancel"))
        assertTrue(PathRules.isDirectReply("/tts/started"))
        assertTrue(PathRules.isDirectReply("/tts/done"))
        assertTrue(PathRules.isOwnerScoped("/tts/done"))
    }

    @Test
    fun `pin routes reuse the surfaces capability`() {
        assertEquals(PluginCapability.SURFACES, PathRules.requiredCapability("/pin/show"))
        assertEquals(PluginCapability.SURFACES, PathRules.requiredCapability("/pin/hide"))
    }

    @Test
    fun `activity routes reuse surfaces and replies stay owner scoped`() {
        assertEquals(
            PluginCapability.SURFACES,
            PathRules.requiredCapability("/activity/start"),
        )
        assertEquals(
            PluginCapability.SURFACES,
            PathRules.requiredCapability("/activity/update"),
        )
        assertEquals(
            PluginCapability.SURFACES,
            PathRules.requiredCapability("/activity/end"),
        )
        assertNull(PathRules.requiredCapability("/activity/action"))
        assertNull(PathRules.requiredCapability("/activity/closed"))
        assertTrue(PathRules.isDirectReply("/activity/action"))
        assertTrue(PathRules.isDirectReply("/activity/closed"))
        assertTrue(PathRules.isOwnerScoped("/activity/action"))
        assertTrue(PathRules.isOwnerScoped("/activity/closed"))
    }
}
