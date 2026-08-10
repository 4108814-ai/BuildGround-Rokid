package com.anezium.rokidbus.plugin.wirelessadb

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class WirelessAdbPluginService : NexusPluginService() {
    private val runtime = WirelessAdbRuntime(
        object : WirelessAdbHost {
            override fun send(path: String, id: String, payload: JSONObject): Boolean =
                nexusClient?.send(path, id, payload) == true
        },
    )

    override fun onCreate() {
        super.onCreate()
        activeRuntime = runtime
        notifyRuntimeChanged()
    }

    override fun onDestroy() {
        runtime.onDisconnected()
        if (activeRuntime === runtime) {
            activeRuntime = null
            notifyRuntimeChanged()
        }
        super.onDestroy()
    }

    override fun onNexusOpen() = Unit

    override fun onNexusClose() = Unit

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusRegistrationState(result: Int) {
        if (result == PluginRegistrationResult.APPROVED) {
            runtime.onConnected()
        } else {
            runtime.onDisconnected()
        }
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) =
        runtime.onMessage(path, id, payload)

    companion object {
        @Volatile
        private var activeRuntime: WirelessAdbRuntime? = null
        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        internal fun runtime(): WirelessAdbRuntime? = activeRuntime

        internal fun observeRuntime(listener: () -> Unit): () -> Unit {
            listeners += listener
            listener()
            return { listeners.remove(listener) }
        }

        private fun notifyRuntimeChanged() {
            listeners.forEach { listener -> runCatching { listener() } }
        }
    }
}
