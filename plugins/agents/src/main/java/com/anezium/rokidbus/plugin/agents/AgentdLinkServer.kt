package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Inbound link from the PC daemons.
 *
 * The phone listens and the computer dials, which is the only direction that
 * needs no setup: Android lets an app accept connections, Windows would demand
 * an administrator firewall rule for the reverse. Discovery follows the same
 * asymmetry — the daemon broadcasts, we answer by unicast, and Windows lets that
 * reply back in because it answers a broadcast the PC itself just sent.
 */
class AgentdLinkServer(
    private val store: AgentSessionStore,
    private val configStore: AgentsConfigStore,
    private val scope: CoroutineScope,
    private val onMachineTrusted: (String) -> Unit = {},
) {
    private var acceptJob: Job? = null
    private var discoveryJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null

    @Volatile private var client: LinkConnection? = null

    fun start() {
        if (acceptJob != null) return
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.CONNECTING, "waiting for a computer")
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
        discoveryJob = scope.launch(Dispatchers.IO) { discoveryLoop() }
    }

    fun stop(clearSessions: Boolean) {
        acceptJob?.cancel()
        acceptJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { discoverySocket?.close() }
        discoverySocket = null
        client?.close()
        client = null
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.DISCONNECTED)
        if (clearSessions) store.replaceProvider(AgentProvider.CLAUDE, emptyList())
    }

    fun openDetail(sessionId: String) {
        send(AgentdProtocolCodec.detailOpen(sessionId))
    }

    fun closeDetail() {
        send(AgentdProtocolCodec.DETAIL_CLOSE)
    }

    /** Callers are UI/service threads; Android forbids socket writes there. */
    private fun send(payload: String) {
        val connection = client ?: return
        scope.launch(Dispatchers.IO) { connection.send(payload) }
    }

    private suspend fun acceptLoop() {
        while (scope.isActive) {
            try {
                val server = ServerSocket(LINK_PORT)
                serverSocket = server
                while (scope.isActive) {
                    val socket = server.accept()
                    // One computer at a time: a second dial replaces the first.
                    client?.close()
                    client = LinkConnection(socket).also { connection ->
                        scope.launch(Dispatchers.IO) { serve(connection) }
                    }
                }
            } catch (_: SocketException) {
                // Socket closed on stop, or the port was momentarily taken.
            } catch (_: Throwable) {
                // Keep listening: a failed accept must never end the loop.
            }
            runCatching { serverSocket?.close() }
            serverSocket = null
            if (!scope.isActive) return
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
        }
    }

    private fun serve(connection: LinkConnection) {
        val codec = AgentdProtocolCodec()
        codec.reset()
        try {
            connection.lines { line ->
                when (val action = codec.parse(line)) {
                    is AgentdAction.Hello -> {
                        val trusted = configStore.isMachineTrusted(action.machineId, action.token)
                        if (!trusted) {
                            configStore.trustMachine(action.machineId, action.token, action.machineName)
                            onMachineTrusted(action.machineName)
                        }
                        connection.send(HELLO_ACK)
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.CONNECTED,
                            action.machineName,
                        )
                    }
                    is AgentdAction.Snapshot ->
                        store.replaceProvider(AgentProvider.CLAUDE, action.sessions)
                    is AgentdAction.Upsert -> store.upsert(action.session)
                    is AgentdAction.Removed -> store.remove(AgentProvider.CLAUDE, action.sessionId)
                    is AgentdAction.Detail ->
                        store.setConversation(AgentProvider.CLAUDE, action.sessionId, action.messages)
                    is AgentdAction.DetailAppend ->
                        store.appendConversation(AgentProvider.CLAUDE, action.sessionId, action.message)
                    is AgentdAction.Send -> connection.send(action.text)
                    is AgentdAction.HelloAcknowledged, AgentdAction.Ignore -> Unit
                }
            }
        } catch (_: Throwable) {
            // Fall through: the connection is finished either way.
        } finally {
            connection.close()
            if (client === connection) {
                client = null
                store.setConnection(
                    AgentProvider.CLAUDE,
                    ConnectionState.CONNECTING,
                    "waiting for a computer",
                )
                store.replaceProvider(AgentProvider.CLAUDE, emptyList())
            }
        }
    }

    private suspend fun discoveryLoop() {
        while (scope.isActive) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                }
                discoverySocket = socket
                val buffer = ByteArray(2048)
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (isDaemonBeacon(packet)) {
                        replyTo(socket, packet.address, packet.port)
                    }
                }
            } catch (_: SocketException) {
                // Closed on stop.
            } catch (_: Throwable) {
                // Ignore and rebind.
            }
            runCatching { discoverySocket?.close() }
            discoverySocket = null
            if (!scope.isActive) return
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
        }
    }

    private fun isDaemonBeacon(packet: DatagramPacket): Boolean {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return json.optString("nexus") == "agentd" && json.optInt("v", -1) == 1
    }

    private fun replyTo(socket: DatagramSocket, address: InetAddress, port: Int) {
        val payload = JSONObject()
            .put("nexus", "agents-phone")
            .put("v", 1)
            .put("port", LINK_PORT)
            .put("name", android.os.Build.MODEL ?: "phone")
            .toString()
            .toByteArray(Charsets.UTF_8)
        runCatching { socket.send(DatagramPacket(payload, payload.size, address, port)) }
    }

    private class LinkConnection(private val socket: Socket) {
        private val writer: BufferedWriter =
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private val reader: BufferedReader =
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        init {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }

        fun lines(onLine: (String) -> Unit) {
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isNotBlank()) onLine(line)
            }
        }

        val id: Int = socket.port + socket.localPort

        @Synchronized
        fun send(payload: String): Boolean = runCatching {
            writer.write(payload)
            writer.write("\n")
            writer.flush()
            true
        }.getOrElse { false }

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val LINK_PORT = 8792
        const val DISCOVERY_PORT = 8793
        private const val RETRY_DELAY_MS = 2000L
        private val HELLO_ACK = JSONObject()
            .put("type", "hello_ack")
            .put("v", 1)
            .toString()
    }
}
