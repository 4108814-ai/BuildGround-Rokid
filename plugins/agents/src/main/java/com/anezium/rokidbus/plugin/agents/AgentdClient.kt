package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class AgentdClient(
    private val httpClient: OkHttpClient,
    private val store: AgentSessionStore,
    private val scope: CoroutineScope,
    private val versionName: String,
) {
    private var loopJob: Job? = null
    @Volatile private var socket: WebSocket? = null

    /** Survives reconnects: the daemon forgets, the wearer should not. */
    @Volatile private var openSessionId: String? = null

    fun openDetail(sessionId: String) {
        openSessionId = sessionId
        socket?.send(AgentdProtocolCodec.detailOpen(sessionId))
    }

    fun closeDetail() {
        openSessionId = null
        socket?.send(AgentdProtocolCodec.DETAIL_CLOSE)
    }

    fun start(config: AgentdConfig) {
        stop(clearSessions = false)
        loopJob = scope.launch {
            var attempt = 0
            while (isActive) {
                store.setConnection(AgentProvider.CLAUDE, ConnectionState.CONNECTING)
                val result = try {
                    connectOnce(config)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    ConnectionOutcome.Retry
                } finally {
                    socket?.cancel()
                    socket = null
                }
                when (result) {
                    is ConnectionOutcome.AuthFailed -> {
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.AUTH_FAILED,
                            result.detail,
                        )
                        return@launch
                    }
                    is ConnectionOutcome.RetryWithDetail -> {
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.DISCONNECTED,
                            result.detail,
                        )
                    }
                    ConnectionOutcome.Retry -> {
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.DISCONNECTED,
                            "Connection lost",
                        )
                    }
                }
                delay(reconnectDelayMs(attempt++))
            }
        }
    }

    fun stop(clearSessions: Boolean) {
        loopJob?.cancel()
        loopJob = null
        socket?.cancel()
        socket = null
        store.setConnection(AgentProvider.CLAUDE, ConnectionState.DISCONNECTED)
        if (clearSessions) store.replaceProvider(AgentProvider.CLAUDE, emptyList())
    }

    private suspend fun connectOnce(config: AgentdConfig): ConnectionOutcome {
        val codec = AgentdProtocolCodec()
        codec.reset()
        val ended = CompletableDeferred<ConnectionOutcome>()
        var connected = false
        val request = Request.Builder()
            .url(webSocketUrl(config.host, config.port))
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(codec.hello(config.token, versionName))) {
                    ended.complete(ConnectionOutcome.Retry)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val action = codec.parse(text)) {
                    is AgentdAction.HelloAcknowledged -> {
                        connected = true
                        store.setConnection(
                            AgentProvider.CLAUDE,
                            ConnectionState.CONNECTED,
                            action.machineName,
                        )
                    }
                    is AgentdAction.Snapshot -> {
                        store.replaceProvider(AgentProvider.CLAUDE, action.sessions)
                        // A fresh connection knows nothing of the open conversation.
                        openSessionId?.let { webSocket.send(AgentdProtocolCodec.detailOpen(it)) }
                    }
                    is AgentdAction.Upsert -> store.upsert(action.session)
                    is AgentdAction.Removed -> {
                        store.remove(AgentProvider.CLAUDE, action.sessionId)
                    }
                    is AgentdAction.Detail -> store.setConversation(
                        AgentProvider.CLAUDE,
                        action.sessionId,
                        action.messages,
                    )
                    is AgentdAction.DetailAppend -> store.appendConversation(
                        AgentProvider.CLAUDE,
                        action.sessionId,
                        action.message,
                    )
                    is AgentdAction.Send -> webSocket.send(action.text)
                    // Only the LAN link sees a daemon hello: here we are the client.
                    is AgentdAction.Hello, AgentdAction.Ignore -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == BAD_TOKEN_CLOSE_CODE) {
                    ended.complete(ConnectionOutcome.AuthFailed("Pairing invalid"))
                } else {
                    ended.complete(ConnectionOutcome.Retry)
                }
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == BAD_TOKEN_CLOSE_CODE) {
                    ended.complete(ConnectionOutcome.AuthFailed("Pairing invalid"))
                } else {
                    ended.complete(
                        if (connected) ConnectionOutcome.Retry
                        else ConnectionOutcome.RetryWithDetail("Connection closed"),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.complete(ConnectionOutcome.Retry)
            }
        }
        socket = httpClient.newWebSocket(request, listener)
        return ended.await()
    }

    private companion object {
        const val BAD_TOKEN_CLOSE_CODE = 4401
    }
}
