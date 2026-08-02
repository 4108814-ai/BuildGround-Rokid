package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSpeechFallbackPolicyTest {
    @Test
    fun `raw capture fallback is limited to unavailable stt starts`() {
        assertTrue(shouldUseRawCaptureFallback(NexusSdkResult.CAPABILITY_NOT_GRANTED))
        assertTrue(shouldUseRawCaptureFallback(NexusSdkResult.CAPABILITY_NOT_AVAILABLE))
        assertFalse(shouldUseRawCaptureFallback(NexusSdkResult.NOT_REGISTERED))
        assertFalse(shouldUseRawCaptureFallback(NexusSdkResult.INVALID_PAYLOAD))
        assertFalse(shouldUseRawCaptureFallback(NexusSdkResult.SENT))
    }

    @Test
    fun `raw capture fallback does not compete with a busy speech lease`() {
        assertTrue(shouldUseRawCaptureFallback(NexusSpeechStopReason.DENIED_NOT_READY))
        assertFalse(shouldUseRawCaptureFallback(NexusSpeechStopReason.DENIED_BUSY))
        assertFalse(shouldUseRawCaptureFallback(NexusSpeechStopReason.DENIED_NO_LINK))
        assertFalse(shouldUseRawCaptureFallback(NexusSpeechStopReason.DENIED_START_FAILED))
        assertFalse(shouldUseRawCaptureFallback(NexusSpeechStopReason.ERROR))
    }

    @Test
    fun `snapshot failures map to stable assistant tool codes`() {
        assertEquals(TOOL_ERROR_CAMERA_BUSY, snapshotToolErrorCode(NexusSnapshotError.BUSY))
        assertEquals(
            TOOL_ERROR_GLASSES_DISCONNECTED,
            snapshotToolErrorCode(NexusSnapshotError.LINK_DOWN),
        )
        assertEquals(TOOL_ERROR_CANCELLED, snapshotToolErrorCode(NexusSnapshotError.CANCELLED))
        listOf(
            NexusSnapshotError.TIMEOUT,
            NexusSnapshotError.CAPTURE_FAILED,
            NexusSnapshotError.ERROR,
        ).forEach { error ->
            assertEquals(TOOL_ERROR_CAPTURE_FAILED, snapshotToolErrorCode(error))
        }

        assertEquals(
            TOOL_ERROR_NOT_AUTHORIZED,
            snapshotStartToolErrorCode(NexusSdkResult.CAPABILITY_NOT_GRANTED),
        )
        assertEquals(
            TOOL_ERROR_GLASSES_DISCONNECTED,
            snapshotStartToolErrorCode(NexusSdkResult.NOT_REGISTERED),
        )
        assertEquals(
            TOOL_ERROR_CAMERA_BUSY,
            snapshotStartToolErrorCode(NexusSdkResult.CAPABILITY_NOT_AVAILABLE),
        )
    }
}
