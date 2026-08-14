package com.buildground.nexus.glasses

import android.os.Handler
import android.os.Looper
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import org.json.JSONObject

/**
 * Minimal BuildGround-owned CXR-S endpoint.
 *
 * It understands only the Hardware Bridge bootstrap protocol. No legacy Nexus
 * bus, registry, updater, plugin store, HTTP proxy or remote command surface is
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
                    val reply = JSONObject()
                        .put("type", "bridge_ready")
                        .put("protocol", PROTOCOL_VERSION)
                        .put("nonce", nonce)
                        .put("companion", "com.buildground.nexus.glasses")
                    send(reply)
                    main.post {
                        connected = true
                        publish("BuildGround Hardware Bridge verified")
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

    private fun send(message: JSONObject): Boolean = runCatching {
        val payload = Caps().apply { write(message.toString()) }
        val result = bridge?.sendMessage(CHANNEL, payload)
        result != null && result >= 0
    }.getOrDefault(false)

    private fun decode(caps: Caps?, data: ByteArray?): String {
        if (data != null && data.isNotEmpty()) {
            val parsed = runCatching { Caps.fromBytes(data) }.getOrNull()
            if (parsed != null && parsed.size() > 0) {
                val value = runCatching { parsed.at(0).string }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
            val raw = runCatching { String(data, Charsets.UTF_8).trim() }.getOrDefault("")
            if (raw.startsWith("{")) return raw
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
