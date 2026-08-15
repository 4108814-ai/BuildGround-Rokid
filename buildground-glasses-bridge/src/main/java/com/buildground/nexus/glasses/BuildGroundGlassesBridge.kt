package com.buildground.nexus.glasses

import android.os.Handler
import android.os.Looper
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject

/**
 * Minimal BuildGround-owned CXR-S endpoint.
 *
 * r23 is diagnostic only: it does not change the transport. It records the
 * exact phone -> glasses challenge count and the result returned by the existing
 * CXRServiceBridge.sendMessage() glasses -> phone call so we can locate the
 * failing hop without changing another variable at the same time.
 */
object BuildGroundGlassesBridge {
    const val CHANNEL = "buildground.nexus.control.v1"
    const val PROTOCOL_VERSION = 1

    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    @Volatile private var connected = false
    @Volatile private var stateListener: ((String) -> Unit)? = null

    @Volatile private var challengeRxCount = 0
    @Volatile private var replyTxAttempts = 0
    @Volatile private var replyTxAccepted = 0
    @Volatile private var lastSendResult: Int? = null
    @Volatile private var lastSendError: String? = null
    @Volatile private var lastNonce = "—"

    fun start(listener: (String) -> Unit) {
        stateListener = listener
        val existing = bridge
        if (existing != null) {
            publishDiagnostics(if (connected) "CXR bridge connected" else "CXR bridge waiting")
            return
        }

        val next = CXRServiceBridge()
        bridge = next
        next.setStatusListener(statusListener)
        val result = next.subscribe(CHANNEL, messageCallback)
        publishDiagnostics("BuildGround CXR endpoint ready (subscribe=$result)")
    }

    fun isConnected(): Boolean = connected

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, deviceType: Int) {
            main.post {
                connected = true
                publishDiagnostics("Phone link connected")
            }
        }

        override fun onDisconnected() {
            main.post {
                connected = false
                publishDiagnostics("Phone link disconnected")
            }
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
            main.post { publishDiagnostics("Phone link connecting") }
        }

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) {
                main.post {
                    this@BuildGroundGlassesBridge.connected = true
                    publishDiagnostics("Phone link connected")
                }
            }
        }

        override fun onAudioNoise(noise: Float) = Unit
        override fun onRokidAccountChanged(account: String?) = Unit
    }

    private val messageCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
            if (msgType != CHANNEL) return
            val text = decode(caps, data)
            if (text.isBlank()) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (message.optInt("protocol", -1) != PROTOCOL_VERSION) return

            when (message.optString("type")) {
                "bridge_challenge" -> {
                    val nonce = message.optString("nonce")
                    if (nonce.isBlank()) return

                    challengeRxCount += 1
                    lastNonce = shortNonce(nonce)
                    val response = JSONObject()
                        .put("type", "bridge_ready")
                        .put("protocol", PROTOCOL_VERSION)
                        .put("nonce", nonce)
                        .put("companion", "com.buildground.nexus.glasses")

                    replyTxAttempts += 1
                    val result = sendWithResult(response)
                    if (result != null && result >= 0) replyTxAccepted += 1

                    main.post {
                        connected = true
                        publishDiagnostics(
                            when {
                                result == null -> "Challenge RX; sendMessage threw/returned no result"
                                result >= 0 -> "Challenge RX; CXR-S accepted reply (result=$result)"
                                else -> "Challenge RX; CXR-S rejected reply (result=$result)"
                            },
                        )
                    }
                }

                "bridge_ping" -> {
                    val nonce = message.optString("nonce")
                    if (nonce.isBlank()) return
                    lastNonce = shortNonce(nonce)
                    replyTxAttempts += 1
                    val result = sendWithResult(
                        JSONObject()
                            .put("type", "bridge_pong")
                            .put("protocol", PROTOCOL_VERSION)
                            .put("nonce", nonce),
                    )
                    if (result != null && result >= 0) replyTxAccepted += 1
                    main.post { publishDiagnostics("Ping RX; sendMessage result=${result ?: "null"}") }
                }
            }
        }
    }

    private fun sendWithResult(message: JSONObject): Int? {
        lastSendError = null
        return try {
            val result = bridge?.sendMessage(
                CHANNEL,
                Caps().apply { write(message.toString()) },
            )
            lastSendResult = result
            result
        } catch (t: Throwable) {
            lastSendResult = null
            lastSendError = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            null
        }
    }

    private fun decode(caps: Caps?, data: ByteArray?): String {
        if (data != null && data.isNotEmpty()) {
            val raw = runCatching { String(data, Charsets.UTF_8).trim() }.getOrDefault("")
            if (raw.startsWith("{")) return raw
            val parsed = runCatching { Caps.fromBytes(data) }.getOrNull()
            if (parsed != null && parsed.size() > 0) {
                val value = runCatching { parsed.at(0).string }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
            if (raw.isNotBlank()) return raw
        }
        if (caps != null && caps.size() > 0) {
            return runCatching { caps.at(0).string }.getOrDefault("")
        }
        return ""
    }

    private fun publishDiagnostics(headline: String) {
        val resultText = lastSendResult?.toString() ?: "—"
        val errorText = lastSendError ?: "none"
        publish(
            buildString {
                append(headline)
                append("\n\nDIAGNOSTICS r23")
                append("\nPhone link: ").append(if (connected) "CONNECTED" else "WAITING")
                append("\nChallenge RX: ").append(challengeRxCount)
                append("\nReply TX attempts: ").append(replyTxAttempts)
                append("\nReply TX accepted: ").append(replyTxAccepted)
                append("\nLast sendMessage result: ").append(resultText)
                append("\nLast send error: ").append(errorText)
                append("\nNonce: ").append(lastNonce)
            },
        )
    }

    private fun shortNonce(value: String): String =
        if (value.length <= 8) value else value.take(8)

    private fun publish(message: String) {
        stateListener?.invoke(message)
    }
}
