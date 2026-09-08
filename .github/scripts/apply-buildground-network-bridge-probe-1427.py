#!/usr/bin/env python3
"""Add a one-screen Network Bridge probe to the existing Nexus Glasses launcher.

This patch is applied after the full BuildGround 1.4.26 generated chain. It does not
change Relay transport, notice rendering, wake policy, autostart ownership, Meetings,
or recorder behavior. It only adds an internal activity that sends /http/request via
RokidBus and auto-opens when MainActivity is launched.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
MAIN = SRC / "MainActivity.kt"
PROBE = SRC / "NetworkBridgeProbeActivity.kt"
MANIFEST = ROOT / "glasses-hub/src/main/AndroidManifest.xml"
GRADLE = ROOT / "glasses-hub/build.gradle.kts"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


probe_source = r'''package com.anezium.rokidbus.glasses

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

private const val TAG = "NEXUS-BRIDGE-INTEGRATED"
private const val PATH_HTTP_REQUEST = "/http/request"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val OPENAI_PROBE_URL = "https://api.openai.com/v1/models"
private const val LINK_TIMEOUT_MS = 20_000L
private const val REQUEST_TIMEOUT_MS = 30_000L

class NetworkBridgeProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private var client: BusClient? = null
    private var requestId: String? = null
    private var totalBytes = 0L
    private var sent = false
    private var finished = false

    private val linkTimeout = Runnable {
        if (!finished && !sent) {
            finished = true
            show(
                "BRIDGE FAIL\n\nPHONE LINK DOWN\n\n" +
                    "NEXUS did not report SPP DATA UP within 20 seconds.\n\n" +
                    "Press BACK to return to NEXUS.",
            )
        }
    }

    private val requestTimeout = Runnable {
        if (!finished && sent) {
            finished = true
            show(
                "BRIDGE TIMEOUT\n\n" +
                    "PHONE LINK: UP\n" +
                    "No terminal HTTP reply returned within 30 seconds.\n\n" +
                    "Press BACK to return to NEXUS.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BusTheme.glassesBg
        window.navigationBarColor = BusTheme.glassesBg

        statusView = TextView(this).apply {
            text = "NEXUS NETWORK BRIDGE\n\nWAITING FOR PHONE LINK..."
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
            clientId = "nexus-network-bridge-probe-1427",
            pathPrefixes = listOf(PATH_HTTP_REPLY, BusPaths.ERROR),
            hubTarget = HubTarget.GLASSES,
        ) { event -> handleEvent(event) }
        client = next
        next.connect()
        main.postDelayed(linkTimeout, LINK_TIMEOUT_MS)
        Log.i(TAG, "Integrated bridge probe started")
    }

    override fun onDestroy() {
        main.removeCallbacks(linkTimeout)
        main.removeCallbacks(requestTimeout)
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
        if (!sppUp || sent || finished) return
        main.removeCallbacks(linkTimeout)
        sendProbe()
    }

    private fun sendProbe() {
        val id = UUID.randomUUID().toString()
        requestId = id
        totalBytes = 0L
        sent = true
        show(
            "NEXUS NETWORK BRIDGE\n\n" +
                "PHONE LINK: UP\n" +
                "REQUESTING OPENAI VIA PHONE...\n\n" +
                "Expected: HTTP 401 (no API key)",
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
        main.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS)
    }

    private fun handleBinary(event: BusEvent.Binary) {
        if (event.path != PATH_HTTP_REPLY || event.id != requestId || finished) return
        val bytes = event.meta.optLong("bytes", event.data.size.toLong())
        if (bytes > 0) totalBytes += bytes
        if (!event.meta.optBoolean("done", false)) return

        main.removeCallbacks(requestTimeout)
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
            val code = event.payload.optString("code", "BUS ERROR")
            val message = event.payload.optString("message", event.payload.toString())
            fail(code, message)
        }
    }

    private fun fail(title: String, detail: String) {
        if (finished) return
        main.removeCallbacks(linkTimeout)
        main.removeCallbacks(requestTimeout)
        finished = true
        Log.w(TAG, "$title: $detail")
        show("BRIDGE FAIL\n\n$title\n$detail\n\nPress BACK to return to NEXUS.")
    }

    private fun show(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusView.text = text
        } else {
            statusView.post { statusView.text = text }
        }
    }
}
'''
PROBE.write_text(probe_source, encoding="utf-8")

replace_once(
    MANIFEST,
    "        <activity\n            android:name=\".MainActivity\"\n            android:exported=\"true\"\n            android:launchMode=\"singleTask\"\n            android:screenOrientation=\"portrait\">\n",
    "        <activity\n            android:name=\".MainActivity\"\n            android:exported=\"true\"\n            android:launchMode=\"singleTask\"\n            android:screenOrientation=\"portrait\">\n",
    "MainActivity manifest anchor",
)

manifest_text = MANIFEST.read_text(encoding="utf-8")
activity_end = '''        </activity>\n\n        <!-- Started by the phone over CXR to begin setup. Exported because it arrives from\n'''
if activity_end not in manifest_text:
    raise SystemExit("MainActivity closing manifest anchor missing")
manifest_text = manifest_text.replace(
    activity_end,
    '''        </activity>\n\n        <activity\n            android:name=".NetworkBridgeProbeActivity"\n            android:excludeFromRecents="true"\n            android:exported="false"\n            android:screenOrientation="portrait" />\n\n        <!-- Started by the phone over CXR to begin setup. Exported because it arrives from\n''',
    1,
)
MANIFEST.write_text(manifest_text, encoding="utf-8")

replace_once(
    MAIN,
    "        GlassesHub.start(applicationContext)\n        insetUnsubscribe = HudTopInset.observe(this, ::applyHudTopInset)\n",
    "        GlassesHub.start(applicationContext)\n        if (savedInstanceState == null) {\n            startActivity(Intent(this, NetworkBridgeProbeActivity::class.java))\n        }\n        insetUnsubscribe = HudTopInset.observe(this, ::applyHudTopInset)\n",
    "auto-open integrated bridge probe",
)

replace_once(GRADLE, "versionCode = 10426", "versionCode = 10427", "versionCode")
replace_once(GRADLE, 'versionName = "1.4.26"', 'versionName = "1.4.27"', "versionName")

checks = {
    PROBE: (
        'class NetworkBridgeProbeActivity : Activity()',
        'https://api.openai.com/v1/models',
        'PATH_HTTP_REQUEST',
        'LinkStateBits.SPP_DATA_UP',
        'BRIDGE OK',
        'BRIDGE TIMEOUT',
        'PHONE LINK DOWN',
    ),
    MAIN: ('startActivity(Intent(this, NetworkBridgeProbeActivity::class.java))',),
    MANIFEST: ('android:name=".NetworkBridgeProbeActivity"',),
    GRADLE: ('versionCode = 10427', 'versionName = "1.4.27"'),
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"Missing 1.4.27 marker in {path}: {marker}")

print("Applied integrated NEXUS Network Bridge probe and forward-versioned Glasses to 1.4.27.")
