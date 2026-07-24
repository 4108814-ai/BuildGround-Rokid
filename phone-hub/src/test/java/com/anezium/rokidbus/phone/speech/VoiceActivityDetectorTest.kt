package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    @Test
    fun noBytesReceivedReachesFirstByteTimeout() {
        val detector = VoiceActivityDetector(VoiceActivityConfig(firstByteTimeoutMs = 50L))
        detector.reset(START_MS)
        assertReason("no-audio-bytes", detector.closeReason(START_MS + 50L))
    }

    @Test
    fun quietBytesReachInitialNoSpeechTimeout() {
        val detector = VoiceActivityDetector(
            VoiceActivityConfig(
                averageAbsThreshold = 300,
                peakAbsThreshold = 1_000,
                initialNoSpeechTimeoutMs = 75L,
            ),
        )
        detector.reset(START_MS)
        val activity = detector.acceptPcm16Le(pcm16Le(10, -12, 8, -7), 0, 8, START_MS + 10L)
        assertFalse(activity.isVoice)
        assertReason("no-vad-speech-timeout", detector.closeReason(START_MS + 75L))
    }

    @Test
    fun loudPcmMarksSpeechDetected() {
        val detector = VoiceActivityDetector(
            VoiceActivityConfig(averageAbsThreshold = 300, peakAbsThreshold = 1_000),
        )
        detector.reset(START_MS)
        val activity = detector.acceptPcm16Le(
            pcm16Le(1_200, -1_400, 1_600),
            0,
            6,
            START_MS + 20L,
        )
        val snapshot = detector.snapshot(START_MS + 20L)
        assertTrue(activity.isVoice)
        assertTrue(snapshot.speechDetected)
        assertEquals(6L, snapshot.totalBytes)
        assertEquals(1_400, snapshot.averageAbs)
        assertEquals(1_600, snapshot.peakAbs)
    }

    @Test
    fun silenceAfterSpeechClosesAfterMinimumCapture() {
        val detector = VoiceActivityDetector(
            VoiceActivityConfig(
                averageAbsThreshold = 300,
                peakAbsThreshold = 1_000,
                minCaptureMs = 100L,
                silenceAfterSpeechMs = 40L,
            ),
        )
        detector.reset(START_MS)
        detector.acceptPcm16Le(pcm16Le(1_200, -1_200), 0, 4, START_MS + 10L)
        assertReason("silence-after-speech", detector.closeReason(START_MS + 100L))
    }

    @Test
    fun maxCaptureReturnsSafetyGuard() {
        val detector = VoiceActivityDetector(
            VoiceActivityConfig(
                firstByteTimeoutMs = 10_000L,
                initialNoSpeechTimeoutMs = 10_000L,
                maxCaptureMs = 100L,
            ),
        )
        detector.reset(START_MS)
        assertReason("safety-max", detector.closeReason(START_MS + 100L))
    }

    private fun assertReason(prefix: String, actual: String?) {
        assertNotNull(actual)
        assertTrue(actual!!.startsWith(prefix))
    }

    private fun pcm16Le(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.toShort().toInt()
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return bytes
    }

    private companion object {
        const val START_MS = 1_000L
    }
}
