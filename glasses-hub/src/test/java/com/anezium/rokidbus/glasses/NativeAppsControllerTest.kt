package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.NativeAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NativeAppsControllerTest {
    @Test
    fun `sanitizes labels before exposing them to the phone`() {
        assertEquals(
            "YouTube",
            NativeAppsController.sanitizeLabel("  You\u0000Tube  ", "app.example.video"),
        )
    }

    @Test
    fun `falls back to package name for an empty label`() {
        assertEquals(
            "app.example.video",
            NativeAppsController.sanitizeLabel("\n\t", "app.example.video"),
        )
    }

    @Test
    fun `trims a catalog to the available transport budget`() {
        val apps = (1..NativeAppContract.MAX_APPS).map { index ->
            NativeAppEntry("app.example.video$index", "Application $index ${"x".repeat(70)}")
        }
        val payload = NativeAppsController.listPayloadWithin(
            requestId = "request-12345678",
            apps = apps,
            maxBytes = 2_200,
        )

        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size <= 2_200)
        val decoded = NativeAppContract.parseListResult(payload)
        assertTrue(decoded != null && decoded.apps.isNotEmpty() && decoded.apps.size < apps.size)
    }
}
