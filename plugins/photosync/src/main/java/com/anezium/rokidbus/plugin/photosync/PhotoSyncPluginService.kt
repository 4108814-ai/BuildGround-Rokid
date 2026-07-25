package com.anezium.rokidbus.plugin.photosync

import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import java.util.UUID

/**
 * Photo Sync has no HUD surface: the transfer lives in the Nexus hub and keeps running while this
 * process is dead. The service exists so the settings screen has a live bus registration to talk
 * through, and so the hub can push status while the screen is open.
 */
class PhotoSyncPluginService : NexusPluginService() {
    private val host = object : PhotoSyncHost {
        override fun send(path: String, payload: JSONObject): Boolean =
            nexusClient?.send(path, UUID.randomUUID().toString(), payload) == true

        override fun log(message: String) = Unit.also { Log.i(TAG, message) }
    }

    private val runtime = PhotoSyncRuntime(host)

    override fun onCreate() {
        super.onCreate()
        activeRuntime = runtime
        notifyRuntimeChanged()
    }

    override fun onDestroy() {
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
            runtime.refresh()
        } else {
            runtime.onDisconnected()
        }
        notifyRuntimeChanged()
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) =
        runtime.onMessage(path, payload)

    companion object {
        private const val TAG = "NexusPhotoSync"

        @Volatile
        private var activeRuntime: PhotoSyncRuntime? = null

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        internal fun runtime(): PhotoSyncRuntime? = activeRuntime

        /** Lets the settings screen re-attach when the service process is (re)created. */
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
