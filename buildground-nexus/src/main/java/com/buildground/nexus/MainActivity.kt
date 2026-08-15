package com.buildground.nexus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.buildground.nexus.hardware.BuildGroundCompanionRemover
import com.buildground.nexus.hardware.BuildGroundCxrBridge
import com.buildground.nexus.hardware.BuildGroundCxrLinkControl
import com.buildground.nexus.hardware.RokidAuthorization
import com.buildground.nexus.hardware.RokidTokenStore
import com.buildground.nexus.security.CompanionApkVerifier
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var verifyButton: Button
    private lateinit var tokenStore: RokidTokenStore
    private lateinit var bridge: BuildGroundCxrBridge
    private lateinit var cxrControl: BuildGroundCxrLinkControl
    private lateinit var companionRemover: BuildGroundCompanionRemover
    private val main = Handler(Looper.getMainLooper())

    private var bridgeState = BuildGroundCxrBridge.State()
    private var controlCxrConnected = false
    private var controlGlassesConnected = false
    private var controlVerified = false
    private var controlTx = 0
    private var controlRx = 0
    private var pendingNonce: String? = null
    private var verifyGeneration = 0L
    private var controlMessage = "CXRLink control idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = RokidTokenStore(this)
        bridge = BuildGroundCxrBridge(this) { state ->
            runOnUiThread {
                bridgeState = state
                renderCombinedState()
            }
        }
        cxrControl = BuildGroundCxrLinkControl(
            context = this,
            glassesPackage = GLASSES_PACKAGE,
            onLinkState = { cxrConnected, glassesConnected ->
                runOnUiThread {
                    controlCxrConnected = cxrConnected
                    controlGlassesConnected = glassesConnected
                    if (!cxrConnected || !glassesConnected) {
                        controlVerified = false
                        pendingNonce = null
                    }
                    controlMessage = when {
                        cxrConnected && glassesConnected -> "Direct CXRLink CUSTOMAPP channel connected"
                        cxrConnected -> "Direct CXRLink connected; waiting for glasses"
                        else -> "Direct CXRLink waiting"
                    }
                    renderCombinedState()
                }
            },
            onCustomCommand = { key, payload -> handleDirectCxrRx(key, payload) },
        )
        companionRemover = BuildGroundCompanionRemover(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
            setBackgroundColor(BG)
        }

        content.addView(TextView(this).apply {
            text = "BUILDGROUND"
            textSize = 30f
            setTextColor(ORANGE)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(TextView(this).apply {
            text = "NEXUS"
            textSize = 18f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 36)
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            setBackgroundColor(SURFACE)
            setPadding(28, 24, 28, 24)
        }
        content.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        content.addView(actionButton("AUTHORIZE HI ROKID") { authorize() })
        content.addView(actionButton("CONNECT GLASSES") { connectBridge() })
        content.addView(actionButton("INSTALL GLASSES BRIDGE APK") { chooseCompanionApk() })
        content.addView(secondaryButton("REMOVE TEST GLASSES BRIDGE") { removeTestCompanion() })
        verifyButton = actionButton("VERIFY HARDWARE BRIDGE") {
            sendDirectCxrChallenge()
        }
        content.addView(verifyButton)
        content.addView(secondaryButton("FORGET ROKID AUTHORIZATION") {
            cxrControl.stop()
            bridge.close()
            tokenStore.clear()
            resetDirectVerification("Authorization removed from this BuildGround Nexus installation.")
        })

        content.addView(TextView(this).apply {
            text = "Offline hardware layer • BuildGround package identity • Direct CXRLink RX • No Anezium registry/updater"
            textSize = 12f
            setTextColor(SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, 34, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(content)
        })

        controlMessage = if (tokenStore.load() != null) {
            "Rokid authorization stored securely. Ready to connect."
        } else {
            "Independent Core ready. Authorize Hi Rokid to start the Hardware Bridge."
        }
        renderCombinedState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AUTH_REQUEST -> handleAuthorizationResult(data)
            APK_REQUEST -> handleCompanionApkResult(resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            connectBridge()
        } else if (requestCode == BLUETOOTH_REQUEST) {
            controlMessage = "Bluetooth permission is required for the Rokid hardware link."
            renderCombinedState()
        }
    }

    override fun onDestroy() {
        companionRemover.close()
        cxrControl.stop()
        bridge.close()
        super.onDestroy()
    }

    private fun handleAuthorizationResult(data: Intent?) {
        val result = RokidAuthorization.parse(data)
        if (!result.success || result.token.isNullOrBlank()) {
            controlMessage = result.message
            renderCombinedState()
            return
        }
        tokenStore.save(result.token)
        controlMessage = "${result.message}. Token encrypted in Android Keystore-backed storage."
        renderCombinedState()
        connectBridge()
    }

    private fun handleCompanionApkResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            controlMessage = "BuildGround glasses APK selection cancelled."
            renderCombinedState()
            return
        }
        val uri = data?.data
        if (uri == null) {
            controlMessage = "No glasses APK was selected."
            renderCombinedState()
            return
        }

        val cachedApk = File(cacheDir, "buildground-glasses-bridge-selected.apk")
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                cachedApk.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not open selected APK")
            cachedApk.isFile && cachedApk.length() > 0L
        }.getOrDefault(false)

        if (!copied) {
            cachedApk.delete()
            controlMessage = "Could not read the selected glasses APK."
            renderCombinedState()
            return
        }

        val verification = CompanionApkVerifier.verify(this, cachedApk)
        if (!verification.trusted) {
            cachedApk.delete()
            controlMessage = verification.message
            renderCombinedState()
            return
        }

        controlMessage = "${verification.message}. Sending only this verified APK to Rokid Glasses."
        renderCombinedState()
        bridge.installCompanion(cachedApk)
    }

    private fun authorize() {
        if (!RokidAuthorization.isHiRokidInstalled(this)) {
            controlMessage = "Global Hi Rokid app is not installed on this phone."
            renderCombinedState()
            return
        }
        if (!RokidAuthorization.launch(this, AUTH_REQUEST)) {
            controlMessage = "Could not open the Hi Rokid authorization screen."
            renderCombinedState()
        }
    }

    private fun connectBridge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
            return
        }

        val token = tokenStore.load()
        if (token.isNullOrBlank()) {
            controlMessage = "Authorize Hi Rokid first."
            renderCombinedState()
            authorize()
            return
        }

        val lifecycleStarted = bridge.connect(token)
        val controlStarted = cxrControl.start(token)
        controlMessage = when {
            lifecycleStarted && controlStarted -> "Starting lifecycle + direct CXRLink CUSTOMAPP channels…"
            lifecycleStarted -> "Lifecycle connected, but direct CXRLink failed to start"
            controlStarted -> "Direct CXRLink started, but lifecycle service failed"
            else -> "Could not start BuildGround Hardware Bridge"
        }
        renderCombinedState()
    }

    private fun sendDirectCxrChallenge() {
        if (!controlCxrConnected || !controlGlassesConnected) {
            controlMessage = "Direct CXRLink CUSTOMAPP channel is not connected"
            renderCombinedState()
            return
        }
        if (!bridgeState.companionOpened) {
            controlMessage = "BuildGround glasses companion is not running yet"
            renderCombinedState()
            return
        }

        val nonce = UUID.randomUUID().toString()
        pendingNonce = nonce
        controlVerified = false
        val generation = ++verifyGeneration
        val message = JSONObject()
            .put("type", "bridge_challenge")
            .put("protocol", PROTOCOL_VERSION)
            .put("nonce", nonce)
            .put("host", "com.buildground.nexus")

        val sent = cxrControl.send(CHANNEL, message.toString())
        controlTx += 1
        controlMessage = if (sent) {
            "Direct CXRLink TX challenge; awaiting glasses reply"
        } else {
            "Direct CXRLink TX challenge failed"
        }
        verifyButton.text = if (sent) "VERIFYING…" else "VERIFY HARDWARE BRIDGE"
        renderCombinedState()

        if (sent) {
            main.postDelayed({
                if (generation != verifyGeneration || controlVerified) return@postDelayed
                pendingNonce = null
                controlMessage = "Direct CXRLink RX timeout"
                renderCombinedState()
            }, VERIFY_TIMEOUT_MS)
        }
    }

    private fun handleDirectCxrRx(key: String, payload: ByteArray) {
        runOnUiThread {
            controlRx += 1
            if (key != CHANNEL) {
                controlMessage = "Direct CXRLink RX callback reached phone with other key: $key"
                renderCombinedState()
                return@runOnUiThread
            }

            val text = decodeCxrPayload(payload)
            val message = runCatching { JSONObject(text) }.getOrNull()
            if (message == null) {
                controlMessage = "Direct CXRLink RX reached phone; payload decode/JSON failed"
                renderCombinedState()
                return@runOnUiThread
            }

            val nonce = message.optString("nonce")
            val expected = pendingNonce
            val type = message.optString("type", "unknown")
            if (message.optInt("protocol", -1) != PROTOCOL_VERSION) {
                controlMessage = "Direct CXRLink RX $type with wrong protocol"
                renderCombinedState()
                return@runOnUiThread
            }
            if (expected.isNullOrBlank() || nonce != expected) {
                controlMessage = "Direct CXRLink RX $type; nonce mismatch"
                renderCombinedState()
                return@runOnUiThread
            }
            if (type != "bridge_ready" || message.optString("companion") != GLASSES_PACKAGE) {
                controlMessage = "Direct CXRLink RX matched nonce but identity/type is wrong"
                renderCombinedState()
                return@runOnUiThread
            }

            verifyGeneration += 1L
            pendingNonce = null
            controlVerified = true
            controlMessage = "BUILDGROUND HARDWARE BRIDGE: VERIFIED"
            renderCombinedState()
        }
    }

    private fun decodeCxrPayload(payload: ByteArray): String {
        val raw = runCatching { String(payload, Charsets.UTF_8).trim() }.getOrDefault("")
        if (raw.startsWith("{")) return raw
        return runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() > 0) caps.at(0).string else ""
        }.getOrDefault("")
    }

    private fun resetDirectVerification(message: String) {
        verifyGeneration += 1L
        pendingNonce = null
        controlVerified = false
        controlCxrConnected = false
        controlGlassesConnected = false
        controlTx = 0
        controlRx = 0
        controlMessage = message
        renderCombinedState()
    }

    private fun removeTestCompanion() {
        val token = tokenStore.load()
        if (token.isNullOrBlank()) {
            controlMessage = "Authorize Hi Rokid before removing the BuildGround test companion."
            renderCombinedState()
            return
        }
        controlMessage = "Removing only com.buildground.nexus.glasses from Rokid Glasses…"
        renderCombinedState()
        companionRemover.remove(token) { result ->
            runOnUiThread {
                if (result.success) {
                    cxrControl.stop()
                    bridge.close()
                    resetDirectVerification(
                        result.message + "\nReconnect glasses, then install the NEW paired BuildGround Glasses Bridge APK.",
                    )
                } else {
                    controlMessage = result.message
                    renderCombinedState()
                }
            }
        }
    }

    private fun chooseCompanionApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        runCatching { startActivityForResult(intent, APK_REQUEST) }
            .onFailure {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(fallback, APK_REQUEST)
            }
    }

    private fun renderCombinedState() {
        if (!::status.isInitialized || !::verifyButton.isInitialized) return
        status.text = buildString {
            append(controlMessage)
            append("\n\nHi Rokid lifecycle: ").append(if (bridgeState.serviceConnected) "CONNECTED" else "OFFLINE")
            append("\nRokid Glasses: ").append(if (bridgeState.glassesConnected) "CONNECTED" else "OFFLINE")
            append("\nDirect CXRLink: ").append(if (controlCxrConnected) "CONNECTED" else "OFFLINE")
            append("\nCXRLink Glasses: ").append(if (controlGlassesConnected) "CONNECTED" else "OFFLINE")
            append("\nBuildGround companion: ").append(
                when {
                    bridgeState.companionOpened -> "RUNNING"
                    bridgeState.companionInstalled -> "INSTALLED"
                    else -> "NOT FOUND"
                },
            )
            append("\nHardware Bridge: ").append(if (controlVerified) "VERIFIED" else "NOT VERIFIED")
            append("\n\nDirect CXRLink TX / RX: ").append(controlTx).append(" / ").append(controlRx)
            append("\nNonce: ").append(pendingNonce?.take(8) ?: if (controlVerified) "MATCHED" else "—")
            append("\nLegacy AIDL diagnostic TX / RX: ").append(bridgeState.txCount).append(" / ").append(bridgeState.rxCount)
        }

        verifyButton.text = when {
            controlVerified -> "HARDWARE BRIDGE VERIFIED"
            pendingNonce != null -> "VERIFYING… TAP TO RETRY"
            else -> "VERIFY HARDWARE BRIDGE"
        }
    }

    private companion object {
        const val AUTH_REQUEST = 5101
        const val BLUETOOTH_REQUEST = 5102
        const val APK_REQUEST = 5103
        const val GLASSES_PACKAGE = "com.buildground.nexus.glasses"
        const val CHANNEL = "buildground.nexus.control.v1"
        const val PROTOCOL_VERSION = 1
        const val VERIFY_TIMEOUT_MS = 9_000L
        val BG = Color.rgb(27, 27, 27)
        val SURFACE = Color.rgb(36, 36, 36)
        val ELEVATED = Color.rgb(46, 46, 46)
        val ORANGE = Color.rgb(255, 122, 0)
        val TEXT = Color.rgb(245, 245, 245)
        val SECONDARY = Color.rgb(184, 184, 184)
    }
}
