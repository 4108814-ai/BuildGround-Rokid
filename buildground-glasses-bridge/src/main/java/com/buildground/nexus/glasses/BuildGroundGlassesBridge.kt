package com.buildground.nexus.glasses

import android.os.Handler
import android.os.Looper
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject

/**
 * Minimal BuildGround-owned CXR-S endpoint.
 *
 * Transport deliberately mirrors the known-working Nexus glasses hub:
 * MsgCallback for phone -> glasses traffic and sendMessage() for glasses -> phone
 * traffic. No Nexus registry, updater, plugin store or remote command surface is
 * present here.
 */
object BuildGroundGlassesBridge {
    const val CHANNEL = "buildground.nexus.control.v1"
    const val PROTOCOL_VERSION = 1

    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    @Volatile private var connected = false
    @Volatile private var stateListener: ((String) -> Unit)? = null

    fun start(listener: (String) -> Unit) {
        stateListener = listener
        val existing = bridge
        if (existing != null) {
            publish(if (connected) "CXR bridge connected" else "CXR bridge waiting")
            return
        }

        val next = CXRServiceBridge()
        bridge = next
        next.setStatusListener(statusListener)
        val result = next.subscribe(CHANNEL, messageCallback)
        publish("BuildGround CXR endpoint ready (subscribe=$result)")
    }

    fun isConnected(): Boolean = connected

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, deviceType: Int) {
            main.post {
                connected = true
                publish("Phone link connected")
            }
        }

        override fun onDisconnected() {
            main.post {
                connected = false
                publish("Phone link disconnected")
            }
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
            main.post { publish("Phone link connecting") }
        }

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) {
                main.post {
                    this@BuildGroundGlassesBridge.connected = true
                    publish("Phone link connected")
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
                    val response = JSONObject()
                        .put("type", "bridge_ready")
                        .put("protocol", PROTOCOL_VERSION)
                        .put("nonce", nonce)
                        .put("companion", "com.buildground.nexus.glasses")
                    val sent = send(response)
                    main.post {
                        connected = true
                        publish(
                            if (sent) {
                                "Hardware Bridge reply sent to phone"
                            } else {
                                "Hardware Bridge challenge received, but TX failed"
                            },
                        )
                    }
                }

                "bridge_ping" -> {
                    val nonce = message.optString("nonce")
                    if (nonce.isBlank()) return
                    send(
                        JSONObject()
                            .put("type", "bridge_pong")
                            .put("protocol", PROTOCOL_VERSION)
                            .put("nonce", nonce),
                    )
                }
            }
        }
    }

    private fun send(message: JSONObject): Boolean =
        runCatching {
            val result = bridge?.sendMessage(
                CHANNEL,
                Caps().apply { write(message.toString()) },
            )
            result != null && result >= 0
        }.getOrDefault(false)

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

    private fun publish(message: String) {
        stateListener?.invoke(message)
    }
}
