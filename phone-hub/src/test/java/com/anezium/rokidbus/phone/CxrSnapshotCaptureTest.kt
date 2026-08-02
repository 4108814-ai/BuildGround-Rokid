package com.anezium.rokidbus.phone

import com.example.cxrglobal.callbacks.IImageStreamCbk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CxrSnapshotCaptureTest {
    private class FakeLink(
        private val respond: (attempt: Int, callback: IImageStreamCbk?) -> Unit,
    ) : CxrSnapshotLink {
        var callback: IImageStreamCbk? = null
        var attempts = 0
        val requests = mutableListOf<Triple<Int, Int, Int>>()

        override fun setImageCallback(callback: IImageStreamCbk?) {
            this.callback = callback
        }

        override fun takePhoto(width: Int, height: Int, quality: Int): Boolean {
            attempts += 1
            requests += Triple(width, height, quality)
            respond(attempts, callback)
            return true
        }
    }

    @Test
    fun `capture retries once uses defaults and clears callback`() = runBlocking {
        val jpeg = byteArrayOf(1, 2, 3)
        val link = FakeLink { attempt, callback ->
            if (attempt == 1) {
                callback?.onImageError(5, "temporary")
            } else {
                callback?.onImageReceived(jpeg)
            }
        }
        val capture = CxrSnapshotCapture(
            attemptTimeoutMs = 100,
            firstAttemptCooldownMs = 0,
            retryCooldownMs = 0,
        )

        assertArrayEquals(jpeg, capture.capture(link))
        assertEquals(
            listOf(Triple(1024, 768, 80), Triple(1024, 768, 80)),
            link.requests,
        )
        assertNull(link.callback)
    }

    @Test
    fun `timeout retries once and clears callback`() {
        val link = FakeLink { _, _ -> Unit }
        val capture = CxrSnapshotCapture(
            attemptTimeoutMs = 5,
            firstAttemptCooldownMs = 0,
            retryCooldownMs = 0,
        )

        val failure = runCatching { runBlocking { capture.capture(link) } }.exceptionOrNull()

        assertTrue(failure is SnapshotCaptureTimeoutException)
        assertEquals(2, link.attempts)
        assertNull(link.callback)
    }
}
