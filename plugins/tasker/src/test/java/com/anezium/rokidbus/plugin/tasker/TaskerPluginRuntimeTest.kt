package com.anezium.rokidbus.plugin.tasker

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class TaskerPluginRuntimeTest {
    private class FakeHost(
        var snapshot: TaskerSnapshot = readySnapshot(TaskerTask("Morning"), TaskerTask("Commute")),
    ) : TaskerRuntimeHost {
        data class Sent(val card: NexusCard?, val show: Boolean, val hidden: Boolean)

        val sent = mutableListOf<Sent>()
        val runTaskNames = mutableListOf<String>()
        var refreshFailure: Throwable? = null
        var runResult = TaskerRunResult("Morning", true, "Sent to Tasker: Morning")
        var suspendRun = false
        private val pendingRuns = mutableListOf<Continuation<TaskerRunResult>>()

        override suspend fun refreshTasker(): TaskerSnapshot {
            refreshFailure?.let { throw it }
            return snapshot
        }

        override suspend fun runTask(name: String): TaskerRunResult {
            runTaskNames += name
            return if (suspendRun) {
                suspendCoroutine { pendingRuns += it }
            } else {
                runResult
            }
        }

        override fun sendCard(card: NexusCard, show: Boolean) {
            sent += Sent(card, show, hidden = false)
        }

        override fun hideSurface() {
            sent += Sent(null, show = false, hidden = true)
        }

        fun completeNextRun(result: TaskerRunResult) {
            pendingRuns.removeAt(0).resume(result)
        }
    }

    private class TestClock(var nowMs: Long = 1_000L)

    private data class Fixture(
        val runtime: TaskerPluginRuntime,
        val host: FakeHost,
        val clock: TestClock,
    )

    private fun fixture(
        dispatcher: CoroutineDispatcher,
        host: FakeHost = FakeHost(),
        clock: TestClock = TestClock(),
    ) = Fixture(
        runtime = TaskerPluginRuntime(host, dispatcher) { clock.nowMs },
        host = host,
        clock = clock,
    )

    private fun input(keyCode: Int) = NexusInputEvent("tasker", keyCode, KeyEvent.ACTION_DOWN)

    @Test
    fun `open shows loading then updates with the task list`() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))

        fixture.runtime.open()
        assertTrue(fixture.host.sent.single().show)
        assertEquals(listOf("Reading Tasker tasks..."), fixture.host.sent.single().card!!.lines)

        runCurrent()
        assertEquals(2, fixture.host.sent.size)
        assertFalse(fixture.host.sent.last().show)
        assertEquals(
            listOf("Morning", "Commute"),
            fixture.host.sent.last().card!!.richLines!!.map { it.text },
        )
        assertTrue(fixture.host.sent.last().card!!.richLines!!.first().selected)
    }

    @Test
    fun `back hides the surface and closes input handling`() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.runtime.open()
        runCurrent()

        fixture.runtime.input(input(KeyEvent.KEYCODE_BACK))

        assertTrue(fixture.host.sent.last().hidden)
        val sentAfterClose = fixture.host.sent.size
        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(sentAfterClose, fixture.host.sent.size)
    }

    @Test
    fun `paired swipe keys within debounce window move only once`() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.runtime.open()
        runCurrent()
        val sentBeforeSwipe = fixture.host.sent.size

        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        fixture.clock.nowMs += 249L
        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_DOWN))

        assertEquals(sentBeforeSwipe + 1, fixture.host.sent.size)
        assertTrue(fixture.host.sent.last().card!!.richLines!![1].selected)
        assertEquals("2/2", fixture.host.sent.last().card!!.subtitle)
    }

    @Test
    fun `tap runs the selected task and renders success and failure`() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.runtime.open()
        runCurrent()
        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        fixture.host.runResult = TaskerRunResult("Commute", true, "Sent to Tasker: Commute")

        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertEquals("Sending: Commute", fixture.host.sent.last().card!!.subtitle)
        runCurrent()
        assertEquals("Sent: Commute", fixture.host.sent.last().card!!.subtitle)

        fixture.host.runResult = TaskerRunResult(
            taskName = "Commute",
            success = false,
            message = "Tasker rejected Commute.",
        )
        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("Sending: Commute", fixture.host.sent.last().card!!.subtitle)
        runCurrent()

        assertEquals(listOf("Commute", "Commute"), fixture.host.runTaskNames)
        assertEquals("Tasker rejected Commute.", fixture.host.sent.last().card!!.subtitle)
    }

    @Test
    fun `task result arriving after close is dropped`() = runTest {
        val host = FakeHost().apply { suspendRun = true }
        val fixture = fixture(StandardTestDispatcher(testScheduler), host)
        fixture.runtime.open()
        runCurrent()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(listOf("Morning"), host.runTaskNames)

        fixture.runtime.close()
        val sentAfterClose = host.sent.size
        host.completeNextRun(TaskerRunResult("Morning", true, "Sent to Tasker: Morning"))
        runCurrent()

        assertEquals(sentAfterClose, host.sent.size)
        assertTrue(host.sent.last().hidden)
    }

    @Test
    fun `task result from before a re-open is dropped`() = runTest {
        val host = FakeHost().apply { suspendRun = true }
        val fixture = fixture(StandardTestDispatcher(testScheduler), host)
        fixture.runtime.open()
        runCurrent()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()

        fixture.runtime.open()
        runCurrent()
        val sentAfterReopen = host.sent.size
        host.completeNextRun(TaskerRunResult("Morning", true, "Sent to Tasker: Morning"))
        runCurrent()

        assertEquals(sentAfterReopen, host.sent.size)
        assertEquals("1/2", host.sent.last().card!!.subtitle)
        assertTrue(host.sent.last().card!!.richLines!!.first().selected)
    }

    @Test
    fun `re-entrant open resets selection status and debounce`() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.runtime.open()
        runCurrent()
        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertEquals("Sending: Commute", fixture.host.sent.last().card!!.subtitle)

        fixture.runtime.open()
        assertTrue(fixture.host.sent.last().show)
        assertEquals(listOf("Reading Tasker tasks..."), fixture.host.sent.last().card!!.lines)
        runCurrent()
        assertEquals("1/2", fixture.host.sent.last().card!!.subtitle)
        assertTrue(fixture.host.sent.last().card!!.richLines!!.first().selected)

        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("2/2", fixture.host.sent.last().card!!.subtitle)
    }

    @Test
    fun `refresh failure renders the fallback status card`() = runTest {
        val host = FakeHost().apply { refreshFailure = IllegalStateException("offline") }
        val fixture = fixture(StandardTestDispatcher(testScheduler), host)

        fixture.runtime.open()
        runCurrent()

        assertFalse(host.sent.last().show)
        assertEquals(
            listOf("Could not read Tasker status.", "Complete setup in the phone app."),
            host.sent.last().card!!.lines,
        )
    }

    private companion object {
        fun readySnapshot(vararg tasks: TaskerTask) = TaskerSnapshot(
            installed = true,
            enabled = true,
            externalAccess = true,
            runPermissionGranted = true,
            tasks = tasks.toList(),
            message = "Ready.",
        )
    }
}
