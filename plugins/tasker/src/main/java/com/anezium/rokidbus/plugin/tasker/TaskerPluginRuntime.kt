package com.anezium.rokidbus.plugin.tasker

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal interface TaskerRuntimeHost {
    suspend fun refreshTasker(): TaskerSnapshot
    suspend fun runTask(name: String): TaskerRunResult
    fun sendCard(card: NexusCard, show: Boolean)
    fun hideSurface()
}

internal class TaskerPluginRuntime(
    private val host: TaskerRuntimeHost,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = TaskerPluginState()
    private var refreshJob: Job? = null
    private var runJob: Job? = null
    private var generation = 0L
    private var open = false
    private var lastDirectionAtMs = Long.MIN_VALUE

    fun open() {
        generation += 1
        refreshJob?.cancel()
        runJob?.cancel()
        open = true
        lastDirectionAtMs = Long.MIN_VALUE
        state.reset()
        host.sendCard(state.card(), show = true)

        val opening = generation
        refreshJob = scope.launch {
            val snapshot = try {
                host.refreshTasker()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TaskerSnapshot(
                    installed = false,
                    enabled = false,
                    externalAccess = false,
                    runPermissionGranted = false,
                    tasks = emptyList(),
                    message = "Could not read Tasker status.",
                )
            }
            if (!isCurrent(opening)) return@launch
            state.applySnapshot(snapshot)
            host.sendCard(state.card(), show = false)
        }
    }

    fun close() {
        generation += 1
        open = false
        refreshJob?.cancel()
        refreshJob = null
        runJob?.cancel()
        runJob = null
        state.reset()
        host.hideSurface()
    }

    fun destroy() {
        close()
        scope.cancel()
    }

    fun input(event: NexusInputEvent) {
        if (!open || event.action != KeyEvent.ACTION_DOWN) return
        when {
            event.keyCode == KeyEvent.KEYCODE_BACK -> close()
            event.keyCode in FORWARD_KEYS -> move(1)
            event.keyCode in BACKWARD_KEYS -> move(-1)
            event.keyCode in TAP_KEYS -> runSelectedTask()
        }
    }

    private fun move(delta: Int) {
        val now = clockMs()
        if (lastDirectionAtMs != Long.MIN_VALUE && now - lastDirectionAtMs < DIRECTION_DEBOUNCE_MS) return
        lastDirectionAtMs = now
        if (state.move(delta)) host.sendCard(state.card(), show = false)
    }

    private fun runSelectedTask() {
        if (runJob?.isActive == true) return
        val task = state.selectedTask() ?: return
        state.setStatus("Sending: ${task.name}")
        host.sendCard(state.card(), show = false)

        val running = generation
        runJob = scope.launch {
            val result = try {
                host.runTask(task.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                TaskerRunResult(
                    taskName = task.name,
                    success = false,
                    message = failure.message ?: "Tasker broadcast failed.",
                )
            }
            if (!isCurrent(running)) return@launch
            state.setStatus(if (result.success) "Sent: ${result.taskName}" else result.message)
            host.sendCard(state.card(), show = false)
        }
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        open && generation == expectedGeneration

    private companion object {
        const val DIRECTION_DEBOUNCE_MS = 250L

        val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        val BACKWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        val TAP_KEYS = setOf(
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
        )
    }
}
