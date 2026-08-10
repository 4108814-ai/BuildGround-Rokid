package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakePhotoToolTest {
    @Test
    fun `photo stage remains tool owned and registry restores thinking`() = runTest {
        val progress = mutableListOf<String>()
        val capabilities = FakeCapabilities(progress) {
            TakePhotoCaptureResult.Failed(TOOL_ERROR_CAPTURE_FAILED)
        }
        val tool = TakePhotoTool(capabilities, elapsedClock = { 100L })
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            progressReporter = progress::add,
        ).newExecutionPhase(TOOLS_WITH_VISION)

        val result = phase.execute(AssistantToolCall("photo", TAKE_PHOTO_TOOL_NAME, "{}"))

        assertNull(tool.progressLabel)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED), result)
        assertEquals(listOf("Photo…", "Thinking…"), progress)
    }

    @Test
    fun `photo cancellation restores thinking after its stage label`() = runTest {
        // The turn goes stale between the stage label and the capture. That is
        // the path that really propagates a cancellation: one thrown inside the
        // capture itself is absorbed by its timeout wrapper and comes back as a
        // capture error instead.
        val progress = mutableListOf<String>()
        val capabilities = FakeCapabilities(progress, staleAfterFirstCheck = true) {
            TakePhotoCaptureResult.Failed(TOOL_ERROR_CAPTURE_FAILED)
        }
        val phase = AssistantToolRegistry(
            definitions = listOf(TakePhotoTool(capabilities, elapsedClock = { 100L })),
            progressReporter = progress::add,
        ).newExecutionPhase(TOOLS_WITH_VISION)

        val thrown = runCatching {
            phase.execute(AssistantToolCall("photo", TAKE_PHOTO_TOOL_NAME, "{}"))
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(listOf("Photo…", "Thinking…"), progress)
    }

    private class FakeCapabilities(
        private val progress: MutableList<String>,
        private val staleAfterFirstCheck: Boolean = false,
        private val capture: suspend () -> TakePhotoCaptureResult,
    ) : TakePhotoToolCapabilities {
        private val session = TakePhotoToolSession("request", 1L)
        private var sessionChecks = 0

        override fun currentSession(): TakePhotoToolSession = session

        override fun isSessionActive(session: TakePhotoToolSession): Boolean {
            sessionChecks += 1
            return !staleAfterFirstCheck || sessionChecks <= 1
        }

        override fun hasCameraGrant(): Boolean = true

        override fun isGlassesConnected(): Boolean = true

        override fun isCameraBusy(): Boolean = false

        override fun showTransient(message: String) {
            progress += message
        }

        override suspend fun captureSnapshotJpeg(): TakePhotoCaptureResult = capture()

        override fun markPhotoCaptured(session: TakePhotoToolSession) = Unit

        override fun retainPhoto(session: TakePhotoToolSession, jpeg: ByteArray) = Unit

        override fun logOutcome(outcome: TakePhotoToolOutcome) = Unit
    }

    private companion object {
        val TOOLS_WITH_VISION = AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = true,
        )
    }
}
