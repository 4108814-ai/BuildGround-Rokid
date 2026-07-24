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
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class OpenClawClient(
    private val httpClient: OkHttpClient,
    private val store: AgentSessionStore,
    private val configStore: AgentsConfigStore,
    private val scope: CoroutineScope,
    private val versionName: String,
) {
    private var loopJob: Job? = null
    @Volatile private var socket: WebSocket? = null

    fun start(config: OpenClawConfig) {
        stop(clearSessions = false)
        loopJob = scope.launch {
            val identity = loadIdentity()
            var attempt = 0
            while (isActive) {
                store.setConnection(AgentProvider.OPENCLAW, ConnectionState.CONNECTING)
                val result = try {
                    connectOnce(config, identity)
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
                            AgentProvider.OPENCLAW,
                            ConnectionState.AUTH_FAILED,
                            result.detail,
                        )
                        return@launch
                    }
                    is ConnectionOutcome.RetryWithDetail -> {
                        store.setConnection(
                            AgentProvider.OPENCLAW,
                            ConnectionState.DISCONNECTED,
                            result.detail,
                        )
                    }
                    ConnectionOutcome.Retry -> {
                        store.setConnection(
                            AgentProvider.OPENCLAW,
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
        store.setConnection(AgentProvider.OPENCLAW, ConnectionState.DISCONNECTED)
        if (clearSessions) store.replaceProvider(AgentProvider.OPENCLAW, emptyList())
    }

    private fun loadIdentity(): OpenClawDeviceIdentity {
        val seed = configStore.openClawSeed()
            ?: Ed25519.generate().seed.also(configStore::saveOpenClawSeed)
        val pair = Ed25519.fromSeed(seed)
        return OpenClawDeviceIdentity(pair.seed, pair.publicKey)
    }

    private suspend fun connectOnce(
        config: OpenClawConfig,
        identity: OpenClawDeviceIdentity,
    ): ConnectionOutcome {
        val ended = CompletableDeferred<ConnectionOutcome>()
        val approvals = ConcurrentHashMap<String, OpenClawApproval>()
        val lock = Any()
        var lastListPayload: String? = null
        var listInFlight = false
        var listRefreshPending = false
        var connected = false
        val request = Request.Builder()
            .url(webSocketUrl(config.host, config.port))
            .build()

        fun renderCached() {
            val payload = synchronized(lock) { lastListPayload } ?: return
            val mapped = runCatching {
                OpenClawProtocol.mapSessions(JSONObject(payload), approvals.values)
            }.getOrNull() ?: return
            store.replaceProvider(AgentProvider.OPENCLAW, mapped)
        }

        lateinit var liveSocket: WebSocket
        fun requestList() {
            synchronized(lock) {
                if (listInFlight) {
                    listRefreshPending = true
                    return
                }
                listInFlight = true
            }
            if (!liveSocket.send(OpenClawProtocol.sessionsListRequest())) {
                synchronized(lock) { listInFlight = false }
                ended.complete(ConnectionOutcome.Retry)
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                liveSocket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("type")) {
                    "event" -> when (val event = OpenClawProtocol.parseEvent(frame)) {
                        is OpenClawEvent.ConnectChallenge -> {
                            val connect = OpenClawProtocol.connectRequest(
                                nonce = event.nonce,
                                token = config.token,
                                identity = identity,
                                versionName = versionName,
                                deviceToken = configStore.openClawDeviceToken(),
                            )
                            webSocket.send(connect)
                        }
                        OpenClawEvent.SessionsChanged -> requestList()
                        is OpenClawEvent.ApprovalRequested -> {
                            approvals[event.approval.id] = event.approval
                            renderCached()
                            requestList()
                        }
                        is OpenClawEvent.ApprovalResolved -> {
                            approvals.remove(event.id)
                            renderCached()
                            requestList()
                        }
                        OpenClawEvent.Unknown -> Unit
                    }
                    "res" -> when (frame.optString("id")) {
                        OpenClawProtocol.CONNECT_ID -> {
                            if (!frame.optBoolean("ok")) {
                                val error = frame.optJSONObject("error")
                                when {
                                    OpenClawProtocol.isAuthError(error) -> ended.complete(
                                        ConnectionOutcome.AuthFailed("Gateway token rejected"),
                                    )
                                    OpenClawProtocol.isPairingError(error) -> ended.complete(
                                        ConnectionOutcome.RetryWithDetail(
                                            "Approve this device in OpenClaw",
                                        ),
                                    )
                                    else -> ended.complete(
                                        ConnectionOutcome.RetryWithDetail("Gateway handshake failed"),
                                    )
                                }
                                return
                            }
                            val payload = frame.optJSONObject("payload")
                            if (payload?.optString("type") != "hello-ok" ||
                                payload.optInt("protocol", -1) != OpenClawProtocol.PROTOCOL_VERSION
                            ) {
                                ended.complete(
                                    ConnectionOutcome.RetryWithDetail(
                                        "Unsupported Gateway protocol",
                                    ),
                                )
                                return
                            }
                            payload.optJSONObject("auth")
                                ?.nullableString("deviceToken")
                                ?.let(configStore::saveOpenClawDeviceToken)
                            approvals.clear()
                            connected = true
                            store.setConnection(
                                AgentProvider.OPENCLAW,
                                ConnectionState.CONNECTED,
                            )
                            requestList()
                            if (!webSocket.send(OpenClawProtocol.sessionsSubscribeRequest())) {
                                ended.complete(ConnectionOutcome.Retry)
                            }
                        }
                        OpenClawProtocol.LIST_ID -> {
                            val refreshAgain = synchronized(lock) {
                                listInFlight = false
                                listRefreshPending.also { listRefreshPending = false }
                            }
                            if (frame.optBoolean("ok")) {
                                frame.optJSONObject("payload")?.let { payload ->
                                    synchronized(lock) { lastListPayload = payload.toString() }
                                    renderCached()
                                }
                            } else {
                                ended.complete(
                                    ConnectionOutcome.RetryWithDetail("sessions.list failed"),
                                )
                            }
                            if (refreshAgain && !ended.isCompleted) {
                                requestList()
                            }
                        }
                        OpenClawProtocol.SUBSCRIBE_ID -> {
                            if (!frame.optBoolean("ok")) {
                                ended.complete(
                                    ConnectionOutcome.RetryWithDetail(
                                        "sessions.subscribe failed",
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(ConnectionOutcome.Retry)
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(
                    if (connected) ConnectionOutcome.Retry
                    else ConnectionOutcome.RetryWithDetail("Gateway closed"),
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.complete(ConnectionOutcome.Retry)
            }
        }
        socket = httpClient.newWebSocket(request, listener)
        return ended.await()
    }
}
