package com.anezium.rokidbus.plugin.tasker

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class TaskerPluginService : NexusPluginService() {
    private var runtime: TaskerPluginRuntime? = null
    private var surface: NexusSurfaceSession? = null
    private val repository by lazy { TaskerRepository(applicationContext) }

    private val runtimeHost = object : TaskerRuntimeHost {
        override suspend fun refreshTasker(): TaskerSnapshot = repository.refresh()

        override suspend fun runTask(name: String): TaskerRunResult = repository.runTask(name)

        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surface ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun hideSurface() {
            surface?.hide()
        }
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onDestroy() {
        runtime?.destroy()
        runtime = null
        surface = null
        super.onDestroy()
    }

    private fun ensureRuntime(): TaskerPluginRuntime =
        runtime ?: TaskerPluginRuntime(runtimeHost).also { runtime = it }

    private companion object {
        const val SURFACE_ID = "tasker"
    }
}
