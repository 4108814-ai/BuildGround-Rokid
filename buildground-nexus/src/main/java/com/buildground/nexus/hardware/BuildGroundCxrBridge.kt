package com.buildground.nexus.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.rokid.cxr.Caps
import com.rokid.sprite.aiapp.externalapp.ICustomCmdCallback
import com.rokid.sprite.aiapp.externalapp.IDeviceStatusCallback
import com.rokid.sprite.aiapp.externalapp.IGlassAppCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService
import org.json.JSONObject
import java.util.UUID

/**
 * BuildGround-owned phone side of the Rokid Hardware Bridge.
 *
 * This binds directly to the Global Hi Rokid MediaStreamService from the
 * official Rokid client-l API. It does not use CxrGlobal, Rokid Nexus,
 * Anezium registry/update infrastructure or the legacy RokidBus protocol.
 */
class BuildGroundCxrBridge(
    context: Context,
    private val listener: Listener,
) {
    data class State(
        val serviceConnected: Boolean = false,
        val glassesConnected: Boolean = false,
        val companionInstalled: Boolean = false,
        val companionOpened: Boolean = false,
        val bridgeVerified: Boolean = false,
        val message: String = "Idle",
    )

    fun interface Listener {
        fun onState(state: State)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IMediaStreamService? = null
    private var bound = false
    private var currentState = State()
    private var pendingChallengeNonce: String? = null

    private val nativeCapsReady: Boolean = runCatching {
        System.loadLibrary("cxr-sock-proto-jni")
        true
    }.getOrDefault(false)

    fun connect(token: String): Boolean {
        if (token.isBlank()) {
            update(message = "No Hi Rokid authorization token")
            return false
        }
        if (bound && service != null) {
            queryAndOpenCompanion()
            return true
        }

        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(RokidAuthorization.GLOBAL_APP_PACKAGE)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, appContext.packageName)

        bound = runCatching {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        update(message = if (bound) "Binding to Hi Rokid…" else "Hi Rokid MediaStreamService bind failed")
        return bound
    }

    fun sendChallenge(): Boolean {
        if (!nativeCapsReady) {
            update(message = "Rokid Caps native library unavailable")
            return false
        }
        if (service == null || !currentState.serviceConnected) {
            update(message = "CXR service is not connected")
            return false
        }
        if (!currentState.companionOpened) {
            queryAndOpenCompanion()
            update(message = "Starting BuildGround glasses companion…")
            return false
        }

        val nonce = UUID.randomUUID().toString()
        pendingChallengeNonce = nonce
        val message = JSONObject()
            .put("type", "bridge_challenge")
            .put("protocol", PROTOCOL_VERSION)
            .put("nonce", nonce)
            .put("host", "com.buildground.nexus")

        val sent = send(message)
        update(
            bridgeVerified = false,
            message = if (sent) "Hardware Bridge challenge sent" else "Hardware Bridge send failed",
        )
        return sent
    }

    fun ping(): Boolean {
        if (!currentState.bridgeVerified) return sendChallenge()
        val nonce = UUID.randomUUID().toString()
        pendingChallengeNonce = nonce
        return send(
            JSONObject()
                .put("type", "bridge_ping")
                .put("protocol", PROTOCOL_VERSION)
                .put("nonce", nonce),
        )
    }

    fun close() {
        val svc = service
        if (svc != null) {
            runCatching { svc.unregisterDeviceStatusCallback(deviceStatusCallback) }
            runCatching { svc.unregisterCustomCmdCallback(customCmdCallback) }
        }
        if (bound) runCatching { appContext.unbindService(serviceConnection) }
        bound = false
        service = null
        pendingChallengeNonce = null
        currentState = State(message = "Hardware Bridge stopped")
        publish()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                onServiceLost("Hi Rokid returned an empty binder")
                return
            }
            val connectedService = IMediaStreamService.Stub.asInterface(binder)
            service = connectedService
            runCatching { connectedService.registerDeviceStatusCallback(deviceStatusCallback) }
            runCatching { connectedService.registerCustomCmdCallback(customCmdCallback) }
            val glasses = runCatching { connectedService.isDeviceConnected }.getOrDefault(false)
            update(
                serviceConnected = true,
                glassesConnected = glasses,
                message = if (glasses) "Hi Rokid connected; checking BuildGround companion…" else "Hi Rokid connected; glasses not connected",
            )
            if (glasses) queryAndOpenCompanion()
        }

        override fun onServiceDisconnected(name: ComponentName?) = onServiceLost("Hi Rokid service disconnected")
        override fun onBindingDied(name: ComponentName?) = onServiceLost("Hi Rokid service binding died")
        override fun onNullBinding(name: ComponentName?) = onServiceLost("Hi Rokid service rejected binding")
    }

    private val deviceStatusCallback = object : IDeviceStatusCallback.Stub() {
        override fun onDeviceConnectChanged(connected: Boolean) {
            update(
                glassesConnected = connected,
                companionInstalled = if (connected) currentState.companionInstalled else false,
                companionOpened = if (connected) currentState.companionOpened else false,
                bridgeVerified = if (connected) currentState.bridgeVerified else false,
                message = if (connected) "Glasses connected; checking BuildGround companion…" else "Glasses disconnected",
            )
            if (connected) queryAndOpenCompanion()
        }

        override fun onDeviceInfoNotifiy(infoJson: String?) = Unit
        override fun onWearingStatusNotify(wearing: Boolean) = Unit
        override fun onCurrentScenesNotify(scenesJson: String?) = Unit
    }

    private val glassAppCallback = object : IGlassAppCallback.Stub() {
        override fun onInstallAppResult(success: Boolean) = Unit
        override fun onUnInstallAppResult(success: Boolean) = Unit
        override fun onStopAppResult(success: Boolean) = Unit

        override fun onQueryAppResult(pkg: String?, installed: Boolean) {
            update(
                companionInstalled = installed,
                companionOpened = if (installed) currentState.companionOpened else false,
                bridgeVerified = if (installed) currentState.bridgeVerified else false,
                message = if (installed) "BuildGround glasses companion found" else "BuildGround glasses companion is not installed",
            )
            if (installed) openCompanion()
        }

        override fun onOpenAppResult(success: Boolean) {
            update(
                companionOpened = success,
                bridgeVerified = false,
                message = if (success) "BuildGround glasses companion started" else "Could not start BuildGround glasses companion",
            )
            if (success) {
                main.postDelayed({ sendChallenge() }, 900L)
                main.postDelayed({ if (!currentState.bridgeVerified) sendChallenge() }, 2200L)
            }
        }
    }

    private val customCmdCallback = object : ICustomCmdCallback.Stub() {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != CHANNEL || payload == null || payload.isEmpty()) return
            val text = decode(payload)
            if (text.isBlank()) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (message.optInt("protocol", -1) != PROTOCOL_VERSION) return

            val nonce = message.optString("nonce")
            if (nonce.isBlank() || nonce != pendingChallengeNonce) return

            when (message.optString("type")) {
                "bridge_ready" -> {
                    if (message.optString("companion") != GLASSES_PACKAGE) return
                    pendingChallengeNonce = null
                    update(bridgeVerified = true, message = "BUILDGROUND HARDWARE BRIDGE: VERIFIED")
                }
                "bridge_pong" -> {
                    pendingChallengeNonce = null
                    update(bridgeVerified = true, message = "Hardware Bridge link OK")
                }
            }
        }
    }

    private fun queryAndOpenCompanion() {
        val svc = service ?: return
        if (!currentState.glassesConnected) return
        runCatching { svc.queryGlassAppInstalled(GLASSES_PACKAGE, glassAppCallback) }
            .onFailure { update(message = "Could not query BuildGround glasses companion") }
    }

    private fun openCompanion() {
        val svc = service ?: return
        runCatching {
            svc.openApp(GLASSES_PACKAGE, "$GLASSES_PACKAGE.MainActivity", glassAppCallback)
        }.onFailure {
            update(message = "Could not open BuildGround glasses companion")
        }
    }

    private fun send(message: JSONObject): Boolean {
        val svc = service ?: return false
        if (!nativeCapsReady) return false
        return runCatching {
            val caps = Caps().apply { write(message.toString()) }
            svc.sendCustomCmd(CHANNEL, caps.serialize()) >= 0
        }.getOrDefault(false)
    }

    private fun decode(payload: ByteArray): String = runCatching {
        val caps = Caps.fromBytes(payload)
        if (caps.size() > 0) caps.at(0).string else ""
    }.getOrDefault("")

    private fun onServiceLost(message: String) {
        service = null
        bound = false
        pendingChallengeNonce = null
        update(
            serviceConnected = false,
            glassesConnected = false,
            companionInstalled = false,
            companionOpened = false,
            bridgeVerified = false,
            message = message,
        )
    }

    private fun update(
        serviceConnected: Boolean = currentState.serviceConnected,
        glassesConnected: Boolean = currentState.glassesConnected,
        companionInstalled: Boolean = currentState.companionInstalled,
        companionOpened: Boolean = currentState.companionOpened,
        bridgeVerified: Boolean = currentState.bridgeVerified,
        message: String = currentState.message,
    ) {
        currentState = State(
            serviceConnected = serviceConnected,
            glassesConnected = glassesConnected,
            companionInstalled = companionInstalled,
            companionOpened = companionOpened,
            bridgeVerified = bridgeVerified,
            message = message,
        )
        publish()
    }

    private fun publish() {
        val snapshot = currentState
        main.post { listener.onState(snapshot) }
    }

    private companion object {
        const val MEDIA_STREAM_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_AUTH_PACKAGE = "auth_package"
        const val GLASSES_PACKAGE = "com.buildground.nexus.glasses"
        const val CHANNEL = "buildground.nexus.control.v1"
        const val PROTOCOL_VERSION = 1
    }
}
