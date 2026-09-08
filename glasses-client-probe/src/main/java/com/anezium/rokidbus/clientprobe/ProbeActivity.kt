package com.anezium.rokidbus.clientprobe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONObject
import java.util.UUID

private const val TAG = "NEXUS-BRIDGE-PROBE"
private const val PATH_HTTP_REQUEST = "/http/request"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val OPENAI_PROBE_URL = "https://api.openai.com/v1/models"
private const val PROBE_TIMEOUT_MS = 30_000L

class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private var client: BusClient? = null
    private var requestId: String? = null
    private var totalBytes = 0L
    private var sent = false
    private var finished = false

    private val timeout = Runnable {
        if (!finished && sent) {
            finished = true
            show(
                "BRIDGE TIMEOUT\n\n" +
                    "Phone link was up, but no terminal HTTP reply returned.\n\n" +
                    "VPN: PHONE ON / GLASSES OFF",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ProbeActivity onCreate")
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg

        statusView = TextView(this).apply {
            text = "NEXUS NETWORK BRIDGE\n\nCONNECTING TO PHONE...\n\nVPN: PHONE ON / GLASSES OFF"
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(BusTheme.phosphor)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(BusTheme.glassesBg)
        }
        setContentView(statusView)

        val next = BusClient(
            context = applicationContext,
            clientId = "network-bridge-probe",
            pathPrefixes = listOf(PATH_HTTP_REPLY),
            hubTarget = HubTarget.GLASSES,
        ) { event -> handleEvent(event) }
        client = next
        next.connect()
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        client?.close()
        client = null
        super.onDestroy()
    }

    private fun handleEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> handleLinkState(event.state)
            is BusEvent.Binary -> handleBinary(event)
            is BusEvent.Message -> handleMessage(event)
            is BusEvent.Error -> fail("CLIENT ERROR", event.message)
        }
    }

    private fun handleLinkState(state: Int) {
        val sppUp = state and LinkStateBits.SPP_DATA_UP != 0
        Log.i(TAG, "linkState=$state sppUp=$sppUp")
        if (!sppUp) {
            if (!sent && !finished) {
                show(
                    "NEXUS NETWORK BRIDGE\n\nWAITING FOR PHONE LINK...\n\n" +
                        "SPP DATA: DOWN\nVPN: PHONE ON / GLASSES OFF",
                )
            }
            return
        }
        if (!sent && !finished) sendProbe()
    }

    private fun sendProbe() {
        val id = UUID.randomUUID().toString()
        requestId = id
        totalBytes = 0L
        sent = true
        show(
            "NEXUS NETWORK BRIDGE\n\nPHONE LINK: UP\nREQUESTING OPENAI VIA PHONE...\n\n" +
                "Expected success signal: HTTP response (401 is OK)",
        )
        Log.i(TAG, "sending OpenAI bridge probe id=$id url=$OPENAI_PROBE_URL")
        val accepted = client?.trySend(
            PATH_HTTP_REQUEST,
            id,
            JSONObject().put("url", OPENAI_PROBE_URL),
        ) == true
        if (!accepted) {
            fail("SEND FAILED", "Glasses hub did not accept /http/request")
            return
        }
        main.postDelayed(timeout, PROBE_TIMEOUT_MS)
    }

    private fun handleBinary(event: BusEvent.Binary) {
        if (event.path != PATH_HTTP_REPLY || event.id != requestId || finished) return
        val bytes = event.meta.optLong("bytes", event.data.size.toLong())
        if (bytes > 0) totalBytes += bytes
        if (!event.meta.optBoolean("done", false)) return

        main.removeCallbacks(timeout)
        finished = true
        val status = event.meta.optInt("status", 0)
        val reportedTotal = event.meta.optLong("totalBytes", totalBytes)
        Log.i(TAG, "bridge reply done id=${event.id} status=$status totalBytes=$reportedTotal")
        if (status > 0) {
            show(
                "BRIDGE OK\n\n" +
                    "OPENAI HTTP $status\n" +
                    "REPLY $reportedTotal bytes\n\n" +
                    "ROKID -> SPP -> PHONE -> VPN -> OPENAI -> PHONE -> ROKID\n\n" +
                    if (status == 401) "401 EXPECTED: no API key used" else "HTTP RESPONSE RECEIVED",
            )
        } else {
            show("BRIDGE FAIL\n\nHTTP reply completed without a valid status code")
        }
    }

    private fun handleMessage(event: BusEvent.Message) {
        if (finished) return
        if (event.path == BusPaths.ERROR && (requestId == null || event.id == requestId)) {
            main.removeCallbacks(timeout)
            finished = true
            val code = event.payload.optString("code", "BUS ERROR")
            val message = event.payload.optString("message", event.payload.toString())
            show("BRIDGE FAIL\n\n$code\n$message")
            Log.w(TAG, "bus error id=${event.id} payload=${event.payload}")
        }
    }

    private fun fail(title: String, detail: String) {
        if (finished) return
        main.removeCallbacks(timeout)
        finished = true
        Log.w(TAG, "$title: $detail")
        show("BRIDGE FAIL\n\n$title\n$detail")
    }

    private fun show(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusView.text = text
        } else {
            statusView.post { statusView.text = text }
        }
    }
}
