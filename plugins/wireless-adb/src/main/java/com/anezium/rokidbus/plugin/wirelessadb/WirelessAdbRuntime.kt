package com.anezium.rokidbus.plugin.wirelessadb

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbContract
import com.anezium.rokidbus.shared.WirelessAdbReply
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

internal interface WirelessAdbHost {
    fun send(path: String, id: String, payload: JSONObject): Boolean
}

internal data class PairingCommands(
    val pair: String,
    val connect: String,
    val expiresAtMillis: Long,
)

internal data class WirelessAdbUiState(
    val connected: Boolean = false,
    val busyAction: WirelessAdbAction? = null,
    val wifiConnected: Boolean = false,
    val enabled: Boolean = false,
    val pairingActive: Boolean = false,
    val host: String? = null,
    val connectPort: Int? = null,
    val commands: PairingCommands? = null,
    val error: String? = null,
)

internal class WirelessAdbRuntime(private val host: WirelessAdbHost) {
    private val listeners = CopyOnWriteArrayList<(WirelessAdbUiState) -> Unit>()
    @Volatile
    private var state = WirelessAdbUiState()
    private var pendingRequestId: String? = null
    private var pendingDeadlineMillis = 0L

    @Synchronized
    fun snapshot(nowMillis: Long = System.currentTimeMillis()): WirelessAdbUiState {
        if (pendingRequestId != null && pendingDeadlineMillis in 1..nowMillis) {
            pendingRequestId = null
            pendingDeadlineMillis = 0L
            state = state.copy(busyAction = null, error = "The glasses did not answer in time.")
        }
        val commands = state.commands
        if (commands != null && commands.expiresAtMillis <= nowMillis) {
            state = state.copy(pairingActive = false, commands = null)
        }
        return state
    }

    fun observe(listener: (WirelessAdbUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot())
        return { listeners.remove(listener) }
    }

    @Synchronized
    fun onConnected() {
        state = state.copy(connected = true, error = null)
        notifyChanged()
        refresh()
    }

    @Synchronized
    fun onDisconnected() {
        pendingRequestId = null
        pendingDeadlineMillis = 0L
        state = WirelessAdbUiState(error = "Waiting for Rokid Nexus.")
        notifyChanged()
    }

    @Synchronized
    fun refresh(): Boolean {
        return request(WirelessAdbAction.STATUS)
    }

    @Synchronized
    fun refreshInBackground(): Boolean = request(WirelessAdbAction.STATUS, showBusy = false)

    fun startPairing(): Boolean = request(WirelessAdbAction.START_PAIRING)

    fun cancelPairing(): Boolean = request(WirelessAdbAction.CANCEL_PAIRING)

    fun disable(): Boolean = request(WirelessAdbAction.DISABLE)

    @Synchronized
    fun onMessage(path: String, id: String, payload: JSONObject) {
        if (path != BusPaths.WIRELESS_ADB_REPLY) return
        val reply = WirelessAdbContract.parseReply(payload) ?: return
        if (id != pendingRequestId) return
        pendingRequestId = null
        pendingDeadlineMillis = 0L
        val updated = stateFromReply(reply)
        if (updated != state) {
            state = updated
            notifyChanged()
        }
    }

    @Synchronized
    private fun request(action: WirelessAdbAction, showBusy: Boolean = true): Boolean {
        if (!state.connected) return false
        if (pendingRequestId != null) {
            if (!showBusy || state.busyAction != null) return false
            pendingRequestId = null
            pendingDeadlineMillis = 0L
        }
        val id = UUID.randomUUID().toString()
        pendingRequestId = id
        pendingDeadlineMillis = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        if (showBusy) {
            state = state.copy(busyAction = action, error = null)
            notifyChanged()
        }
        val sent = host.send(BusPaths.WIRELESS_ADB_REQUEST, id, WirelessAdbContract.request(action))
        if (!sent) {
            pendingRequestId = null
            pendingDeadlineMillis = 0L
            state = state.copy(busyAction = null, error = "The request could not reach Rokid Nexus.")
            notifyChanged()
        }
        return sent
    }

    private fun stateFromReply(reply: WirelessAdbReply): WirelessAdbUiState {
        val replyHost = reply.host
        val pairingPort = reply.pairingPort
        val pairingCode = reply.pairingCode
        val connectPort = reply.connectPort
        val expiresAtMillis = reply.expiresAtMillis
        val newCommands = if (
            reply.success &&
            reply.action == WirelessAdbAction.START_PAIRING &&
            replyHost != null &&
            pairingPort != null &&
            pairingCode != null &&
            connectPort != null &&
            expiresAtMillis != null
        ) {
            PairingCommands(
                pair = "adb pair $replyHost:$pairingPort $pairingCode",
                connect = "adb connect $replyHost:$connectPort",
                expiresAtMillis = expiresAtMillis,
            )
        } else if (reply.pairingActive) {
            state.commands
        } else {
            null
        }
        return WirelessAdbUiState(
            connected = true,
            wifiConnected = reply.wifiConnected,
            enabled = reply.enabled,
            pairingActive = reply.pairingActive,
            host = reply.host,
            connectPort = reply.connectPort,
            commands = newCommands,
            error = if (reply.success) null else reply.message ?: reply.errorCode ?: "Wireless debugging failed.",
        )
    }

    private fun notifyChanged() {
        val snapshot = snapshot()
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private companion object {
        // The glasses chain behind START_PAIRING can legitimately take ~41 s at worst
        // (privileged bridge enable, then NSD discovery around the pairing service).
        // Giving up earlier would discard the only reply that carries the pairing code.
        const val REQUEST_TIMEOUT_MS = 45_000L
    }
}
