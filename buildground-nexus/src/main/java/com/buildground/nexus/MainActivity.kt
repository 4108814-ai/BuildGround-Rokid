package com.buildground.nexus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.buildground.nexus.hardware.BuildGroundCxrBridge
import com.buildground.nexus.hardware.RokidAuthorization
import com.buildground.nexus.hardware.RokidTokenStore
import com.buildground.nexus.security.CompanionApkVerifier
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var verifyButton: Button
    private lateinit var tokenStore: RokidTokenStore
    private lateinit var bridge: BuildGroundCxrBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = RokidTokenStore(this)
        bridge = BuildGroundCxrBridge(this) { state -> renderState(state) }

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
        verifyButton = actionButton("VERIFY HARDWARE BRIDGE") {
            verifyButton.text = "VERIFYING…"
            bridge.sendChallenge()
        }
        content.addView(verifyButton)
        content.addView(secondaryButton("FORGET ROKID AUTHORIZATION") {
            bridge.close()
            tokenStore.clear()
            status.text = "Authorization removed from this BuildGround Nexus installation."
            verifyButton.text = "VERIFY HARDWARE BRIDGE"
        })

        content.addView(TextView(this).apply {
            text = "Offline hardware layer • BuildGround package identity • Same-signer companion policy • No Anezium registry/updater"
            textSize = 12f
            setTextColor(SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, 34, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(content)
        })

        status.text = if (tokenStore.load() != null) {
            "Rokid authorization stored securely.\nReady to connect."
        } else {
            "Independent Core ready.\nAuthorize Hi Rokid to start the Hardware Bridge."
        }
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
            status.text = "Bluetooth permission is required for the Rokid hardware link."
        }
    }

    override fun onDestroy() {
        bridge.close()
        super.onDestroy()
    }

    private fun handleAuthorizationResult(data: Intent?) {
        val result = RokidAuthorization.parse(data)
        if (!result.success || result.token.isNullOrBlank()) {
            status.text = result.message
            return
        }
        tokenStore.save(result.token)
        status.text = "${result.message}. Token encrypted in Android Keystore-backed storage."
        connectBridge()
    }

    private fun handleCompanionApkResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            status.text = "BuildGround glasses APK selection cancelled."
            return
        }
        val uri = data?.data
        if (uri == null) {
            status.text = "No glasses APK was selected."
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
            status.text = "Could not read the selected glasses APK."
            return
        }

        val verification = CompanionApkVerifier.verify(this, cachedApk)
        if (!verification.trusted) {
            cachedApk.delete()
            status.text = verification.message
            return
        }

        status.text = "${verification.message}. Sending only this verified APK to Rokid Glasses."
        bridge.installCompanion(cachedApk)
    }

    private fun authorize() {
        if (!RokidAuthorization.isHiRokidInstalled(this)) {
            status.text = "Global Hi Rokid app is not installed on this phone."
            return
        }
        if (!RokidAuthorization.launch(this, AUTH_REQUEST)) {
            status.text = "Could not open the Hi Rokid authorization screen."
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
            status.text = "Authorize Hi Rokid first."
            authorize()
            return
        }
        bridge.connect(token)
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

    private fun renderState(state: BuildGroundCxrBridge.State) {
        runOnUiThread {
            status.text = buildString {
                append(state.message)
                append("\n\nHi Rokid service: ").append(if (state.serviceConnected) "CONNECTED" else "OFFLINE")
                append("\nRokid Glasses: ").append(if (state.glassesConnected) "CONNECTED" else "OFFLINE")
                append("\nCXR CUSTOMAPP: ").append(if (state.customAppConnected) "CONNECTED" else "OFFLINE")
                append("\nBuildGround companion: ").append(
                    when {
                        state.companionOpened -> "RUNNING"
                        state.companionInstalled -> "INSTALLED"
                        else -> "NOT FOUND"
                    },
                )
                append("\nHardware Bridge: ").append(if (state.bridgeVerified) "VERIFIED" else "NOT VERIFIED")
                append("\n\nHandshake: ").append(state.handshakePhase)
                append("\nTX / RX: ").append(state.txCount).append(" / ").append(state.rxCount)
                append("\nNonce: ").append(state.nonceStatus)
            }

            verifyButton.text = when {
                state.bridgeVerified -> "HARDWARE BRIDGE VERIFIED"
                state.handshakePhase.startsWith("TX_") || state.handshakePhase.startsWith("RX_") -> "VERIFYING… TAP TO RESEND"
                else -> "VERIFY HARDWARE BRIDGE"
            }
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.BLACK)
        setBackgroundColor(ORANGE)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 22, 0, 0) }
    }

    private fun secondaryButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(TEXT)
        setBackgroundColor(ELEVATED)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 18, 0, 0) }
    }

    private companion object {
        const val AUTH_REQUEST = 5101
        const val BLUETOOTH_REQUEST = 5102
        const val APK_REQUEST = 5103
        val BG = Color.rgb(27, 27, 27)
        val SURFACE = Color.rgb(36, 36, 36)
        val ELEVATED = Color.rgb(46, 46, 46)
        val ORANGE = Color.rgb(255, 122, 0)
        val TEXT = Color.rgb(245, 245, 245)
        val SECONDARY = Color.rgb(184, 184, 184)
    }
}
