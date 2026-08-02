package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantUiControllerTest {
    @Test
    fun `transient states use notices when supported and cards in legacy mode`() =
        runTest {
            val noticeRenderer = FakeRenderer(supportsNotice = true)
            val noticeController = controller(noticeRenderer)
            noticeController.onOpen()
            noticeController.cancelLauncherHint()

            noticeController.showTransient("Listening…", legacyForceShow = true)
            noticeController.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.ShowNotice("Assistant", "Listening…"),
                    RenderCall.UpdateNotice("Thinking…"),
                ),
                noticeRenderer.calls,
            )
            noticeController.onClose()

            val legacyRenderer = FakeRenderer(supportsNotice = false)
            val legacyController = controller(legacyRenderer)
            legacyController.onOpen()
            legacyController.cancelLauncherHint()

            legacyController.showTransient("Listening…", legacyForceShow = true)
            legacyController.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.ShowCard(listOf("Listening…"), forceShow = true),
                    RenderCall.ShowCard(listOf("Thinking…"), forceShow = false),
                ),
                legacyRenderer.calls,
            )
            legacyController.onClose()

            val openCardRenderer = FakeRenderer(supportsNotice = true)
            val openCardController = controller(openCardRenderer)
            openCardController.onOpen()
            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()

            openCardController.beginGestureFlow()
            openCardController.showTransient("Listening…")

            // An open card stays the render target for the whole interaction:
            // hiding it here would read as a self-close to the hub.
            assertEquals(
                listOf(
                    RenderCall.ShowCard(
                        listOf(AssistantUiController.LAUNCHER_HINT),
                        forceShow = true,
                    ),
                    RenderCall.ShowCard(listOf("Listening…"), forceShow = false),
                ),
                openCardRenderer.calls,
            )
            openCardController.onClose()
        }

    @Test
    fun `transcript updates are throttled latest wins and the tail is retained`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            val finalTail = "tail-" + "z".repeat(195)
            val longTranscript = "discarded-prefix-".repeat(20) + finalTail
            controller.showTranscript("first partial")
            controller.showTranscript("superseded partial")
            controller.showTranscript(longTranscript)

            assertEquals(
                listOf(RenderCall.UpdateNotice("first partial")),
                renderer.calls,
            )

            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS - 1)
            runCurrent()
            assertEquals(1, renderer.calls.size)

            advanceTimeBy(1)
            runCurrent()

            val throttledBody = (renderer.calls.last() as RenderCall.UpdateNotice).body.orEmpty()
            assertTrue(throttledBody.startsWith("${AssistantUiController.ELLIPSIS} "))
            assertTrue(throttledBody.endsWith(finalTail))
            assertTrue(throttledBody.length <= AssistantUiController.MAX_NOTICE_BODY_CHARS)
            assertEquals(2, renderer.calls.size)

            controller.showTranscript("the trailing partial")
            controller.showTransient("Thinking…")

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice("the trailing partial"),
                    RenderCall.UpdateNotice("Thinking…"),
                ),
                renderer.calls.takeLast(2),
            )

            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS)
            runCurrent()
            assertEquals(4, renderer.calls.size)
            controller.onClose()
        }

    @Test
    fun `legacy mode ignores speech partials`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = false)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…", legacyForceShow = true)
            renderer.calls.clear()

            controller.showTranscript("ignored partial")
            advanceTimeBy(AssistantUiController.TRANSCRIPT_UPDATE_INTERVAL_MS)
            runCurrent()

            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `newer notice state cancels error hide and latest error hides after deadline`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            controller.showError("Speech is busy. Try again.")

            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS - 1)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            controller.showTransient("Listening…")
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            controller.showError("Didn't catch that")
            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()

            assertEquals(1, renderer.calls.count { it == RenderCall.HideNotice })
            controller.onClose()
        }

    @Test
    fun `gesture claim cancels deferred hint while launcher open shows it`() =
        runTest {
            val launcherRenderer = FakeRenderer(supportsNotice = true)
            val launcherController = controller(launcherRenderer)
            launcherController.onOpen()

            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS - 1)
            runCurrent()
            assertTrue(launcherRenderer.calls.isEmpty())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(
                    RenderCall.ShowCard(
                        listOf(AssistantUiController.LAUNCHER_HINT),
                        forceShow = true,
                    ),
                ),
                launcherRenderer.calls,
            )
            launcherController.onClose()

            val gestureRenderer = FakeRenderer(supportsNotice = true)
            val gestureController = controller(gestureRenderer)
            gestureController.onOpen()
            gestureController.cancelLauncherHint()

            advanceTimeBy(AssistantUiController.LAUNCHER_HINT_DELAY_MS)
            runCurrent()

            assertTrue(gestureRenderer.calls.isEmpty())
            gestureController.onClose()
        }

    @Test
    fun `user notice close cancels pipeline and capture without touching surface`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            var pipelineCancels = 0
            var captureResets = 0
            val controller = AssistantUiController(
                scope = this,
                renderer = renderer,
                cancelPipeline = { pipelineCancels += 1 },
                resetCapture = { captureResets += 1 },
            )
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            controller.onNoticeClosed(NexusNoticeCloseReason.OWNER)
            assertEquals(0, pipelineCancels)
            assertEquals(0, captureResets)

            controller.showTransient("Listening…")
            renderer.calls.clear()
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)

            assertEquals(1, pipelineCancels)
            assertEquals(1, captureResets)
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `answers stay on the notice band with head truncation and no success hide`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…")
            renderer.calls.clear()

            val longAnswer = "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS) + "tail"
            controller.showAnswer(
                body = longAnswer,
                legacyCardLines = listOf("legacy answer"),
            )
            controller.showAnswer(
                body = "Final answer",
                legacyCardLines = listOf("legacy final"),
            )

            assertEquals(
                listOf(
                    RenderCall.UpdateNotice(
                        "A".repeat(AssistantUiController.MAX_NOTICE_BODY_CHARS - 1) +
                            AssistantUiController.ELLIPSIS,
                    ),
                    RenderCall.UpdateNotice("Final answer"),
                ),
                renderer.calls,
            )
            assertTrue(renderer.calls.none { it is RenderCall.ShowCard })
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })

            advanceTimeBy(AssistantUiController.ERROR_NOTICE_DURATION_MS)
            runCurrent()
            assertTrue(renderer.calls.none { it == RenderCall.HideNotice })
            controller.onClose()
        }

    @Test
    fun `legacy answers keep force show then update card behavior`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = false)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Thinking…", legacyForceShow = true)
            renderer.calls.clear()

            controller.showAnswer(
                body = "First",
                legacyCardLines = listOf("First"),
            )
            controller.showAnswer(
                body = "First chunk",
                legacyCardLines = listOf("First chunk"),
            )

            assertEquals(
                listOf(
                    RenderCall.ShowCard(listOf("First"), forceShow = true),
                    RenderCall.ShowCard(listOf("First chunk"), forceShow = false),
                ),
                renderer.calls,
            )
            controller.onClose()
        }

    @Test
    fun `in-flight band states are kept alive and terminal states stop the keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            renderer.calls.clear()

            // A wearer slow to start speaking produces no updates; the band's
            // TTL must be restarted for them.
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(RenderCall.UpdateNotice("Listening…")), renderer.calls)

            // The keepalive resends the freshest in-flight body, not the first.
            controller.showTransient("Thinking…")
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(RenderCall.UpdateNotice("Thinking…")), renderer.calls)

            // An answer owns its own TTL; keeping it alive would pin the band.
            controller.showAnswer("Done.", legacyCardLines = listOf("Done."))
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    @Test
    fun `errors and user close stop the keepalive`() =
        runTest {
            val renderer = FakeRenderer(supportsNotice = true)
            val controller = controller(renderer)
            controller.onOpen()
            controller.cancelLauncherHint()
            controller.showTransient("Listening…")
            controller.showError("Didn't catch that")
            renderer.calls.clear()

            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.none { it is RenderCall.UpdateNotice })

            controller.showTransient("Listening…")
            controller.onNoticeClosed(NexusNoticeCloseReason.USER)
            renderer.calls.clear()
            advanceTimeBy(AssistantUiController.NOTICE_KEEPALIVE_INTERVAL_MS * 3)
            runCurrent()
            assertTrue(renderer.calls.isEmpty())
            controller.onClose()
        }

    private fun TestScope.controller(renderer: FakeRenderer): AssistantUiController =
        AssistantUiController(
            scope = this,
            renderer = renderer,
            cancelPipeline = {},
            resetCapture = {},
        )

    private sealed interface RenderCall {
        data class ShowNotice(
            val title: String?,
            val body: String?,
        ) : RenderCall

        data class UpdateNotice(
            val body: String?,
        ) : RenderCall

        data object HideNotice : RenderCall


        data class ShowCard(
            val lines: List<String>,
            val forceShow: Boolean,
        ) : RenderCall
    }

    private class FakeRenderer(
        private val supportsNotice: Boolean,
    ) : AssistantUiRenderer {
        val calls = mutableListOf<RenderCall>()

        override val supportsNoticeSurface: Boolean
            get() = supportsNotice

        override fun showNotice(notice: NexusNotice): NexusSdkResult {
            calls += RenderCall.ShowNotice(notice.title, notice.body)
            return NexusSdkResult.SENT
        }

        override fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult {
            calls += RenderCall.UpdateNotice(update.body)
            return NexusSdkResult.SENT
        }

        override fun hideNotice(): NexusSdkResult {
            calls += RenderCall.HideNotice
            return NexusSdkResult.SENT
        }

        override fun showCard(
            lines: List<String>,
            forceShow: Boolean,
        ): NexusSdkResult {
            calls += RenderCall.ShowCard(lines, forceShow)
            return NexusSdkResult.SENT
        }
    }
}
