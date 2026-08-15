package com.buildground.nexus.hardware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.rokid.cxr.Caps
import com.rokid.sprite.aiapp.externalapp.ICustomCmdCallback
import com.rokid.sprite.aiapp.externalapp.IDeviceStatusCallback
import com.rokid.sprite.aiapp.externalapp.IGlassAppCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * BuildGround-owned phone side of the Rokid Hardware Bridge.
 *
 * r22 deliberately uses exactly one Hi Rokid MediaStreamService binding and one
 * ICustomCmdCallback for lifecycle + bidirectional control traffic. This mirrors
 * the proven CxrGlobal transport semantics without retaining a second CXR session
 * or any Anezium runtime dependency.
 */
class BuildGroundCxrBridge(
    context: Context,
    private val listener: Listener,
) {
    data class State(
        val serviceConnected: Boolean = false,
        val glassesConnected: Boolean = false,
        val customAppConnected: Boolean = false,
        val companionInstalled: Boolean = false,
        val companionOpened: Boolean = false,
        val bridgeVerified: Boolean = false,
        val handshakePhase: String = "IDLE",
        val txCount: Int = 0,
        val rxCount: Int = 0,
        val nonceStatus: String = "—",
        val message: String = "Idle",
    )

    fun interface Listener {
        fun onState(state: State)
    }

    private data class PendingChallenge(
        val nonce: String,
        val generation: Long,
        val startedAtMs: Long,
        var attempts: Int = 0,
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IMediaStreamService? = null
    private var bound = false
    private var currentState = State()
    private var pendingChallenge: PendingChallenge? = null
    private var lastVerifiedNonce: String? = null
    private var challengeGeneration = 0L
    private var installProbeGeneration = 0L

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
            rearmCustomCmdCallback()
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

        update(
            customAppConnected = false,
            handshakePhase = if (bound) "BINDING" else "BIND_FAILED",
            message = if (bound) {
                "Binding single BuildGround Hardware Bridge transport to Hi Rokid…"
            } else {
                "Hi Rokid MediaStreamService bind failed"
            },
        )
        return bound
    }

    fun installCompanion(apk: File): Boolean {
        val svc = service
        if (svc == null || !currentState.serviceConnected) {
            update(message = "Connect Hi Rokid before installing the glasses companion")
            return false
        }
        if (!currentState.glassesConnected) {
            update(message = "Connect Rokid Glasses before installing the companion")
            return false
        }
        if (!apk.isFile || apk.length() <= 0L) {
            update(message = "Selected BuildGround glasses APK is invalid")
            return false
        }

        installProbeGeneration += 1L
        update(
            companionOpened = false,
            bridgeVerified = false,
            handshakePhase = "INSTALLING",
            message = "Installing verified BuildGround glasses companion…",
        )

        return runCatching {
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                svc.uploadAndInstallApk(apk.name, fd, glassAppCallback)
            }
            true
        }.onFailure {
            update(message = "BuildGround glasses companion install request failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    fun sendChallenge(): Boolean {
        if (!nativeCapsReady) {
            update(handshakePhase = "BLOCKED", message = "Rokid Caps native library unavailable")
            return false
        }
        if (service == null || !currentState.serviceConnected) {
            update(handshakePhase = "BLOCKED", message = "Hi Rokid service is not connected")
            return false
        }
        if (!currentState.customAppConnected) {
            update(handshakePhase = "BLOCKED", message = "Hi Rokid custom command transport is not connected")
            return false
        }
        if (!currentState.companionOpened) {
            queryAndOpenCompanion()
            update(handshakePhase = "WAITING_COMPANION", message = "Starting BuildGround glasses companion…")
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val active = pendingChallenge
        if (active != null && now - active.startedAtMs < CHALLENGE_TIMEOUT_MS) {
            return transmitChallenge(active, manualRetry = true)
        }

        val generation = ++challengeGeneration
        val pending = PendingChallenge(
            nonce = UUID.randomUUID().toString(),
            generation = generation,
            startedAtMs = now,
        )
        pendingChallenge = pending
        lastVerifiedNonce = null
        update(
            bridgeVerified = false,
            handshakePhase = "TX_READY",
            nonceStatus = "PENDING ${shortNonce(pending.nonce)}",
            message = "Hardware Bridge verification started",
        )

        val sent = transmitChallenge(pending, manualRetry = false)
        scheduleChallengeRetries(pending)
        return sent
    }

    fun ping(): Boolean = sendChallenge()

    fun close() {
        installProbeGeneration += 1L
        challengeGeneration += 1L
        pendingChallenge = null
        lastVerifiedNonce = null

        val svc = service
        if (svc != null) {
            runCatching { svc.unregisterDeviceStatusCallback(deviceStatusCallback) }
            runCatching { svc.unregisterCustomCmdCallback(customCmdCallback) }
        }
        if (bound) runCatching { appContext.unbindService(serviceConnection) }
        bound = false
        service = null
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
            rearmCustomCmdCallback(connectedService)

            val glasses = runCatching { connectedService.isDeviceConnected }.getOrDefault(false)
            update(
                serviceConnected = true,
                glassesConnected = glasses,
                customAppConnected = glasses,
                handshakePhase = if (glasses) "TRANSPORT_READY" else "WAITING_GLASSES",
                message = if (glasses) {
                    "Single Hi Rokid command transport connected; checking BuildGround companion…"
                } else {
                    "Hi Rokid connected; glasses not connected"
                },
            )
            if (glasses) queryAndOpenCompanion()
        }

        override fun onServiceDisconnected(name: ComponentName?) = onServiceLost("Hi Rokid service disconnected")
        override fun onBindingDied(name: ComponentName?) = onServiceLost("Hi Rokid service binding died")
        override fun onNullBinding(name: ComponentName?) = onServiceLost("Hi Rokid service rejected binding")
    }

    private val deviceStatusCallback = object : IDeviceStatusCallback.Stub() {
        override fun onDeviceConnectChanged(connected: Boolean) {
            if (!connected) {
                challengeGeneration += 1L
                pendingChallenge = null
            } else {
                rearmCustomCmdCallback()
            }

            update(
                glassesConnected = connected,
                customAppConnected = connected && currentState.serviceConnected,
                companionInstalled = if (connected) currentState.companionInstalled else false,
                companionOpened = if (connected) currentState.companionOpened else false,
                bridgeVerified = if (connected) currentState.bridgeVerified else false,
                handshakePhase = if (connected) "TRANSPORT_READY" else "GLASSES_OFFLINE",
                message = if (connected) {
                    "Glasses connected on single Hi Rokid transport; checking BuildGround companion…"
                } else {
                    "Glasses disconnected"
                },
            )
            if (connected) queryAndOpenCompanion()
        }

        override fun onDeviceInfoNotifiy(infoJson: String?) = Unit
        override fun onWearingStatusNotify(wearing: Boolean) = Unit
        override fun onCurrentScenesNotify(scenesJson: String?) = Unit
    }

    private val glassAppCallback = object : IGlassAppCallback.Stub() {
        override fun onInstallAppResult(success: Boolean) {
            update(
                companionOpened = false,
                bridgeVerified = false,
                handshakePhase = "INSTALL_CONFIRM",
                message = if (success) {
                    "Rokid accepted install; confirming package on glasses…"
                } else {
                    "Rokid install callback returned false; checking actual package state…"
                },
            )
            schedulePostInstallQueries(installProbeGeneration)
        }

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
            if (success) rearmCustomCmdCallback()
            update(
                companionOpened = success,
                bridgeVerified = false,
                handshakePhase = if (success) "COMPANION_RUNNING" else "COMPANION_OPEN_FAILED",
                message = if (success) "BuildGround glasses companion started" else "Could not start BuildGround glasses companion",
            )
            if (success) {
                main.postDelayed({ if (!currentState.bridgeVerified) sendChallenge() }, 700L)
            }
        }
    }

    private val customCmdCallback = object : ICustomCmdCallback.Stub() {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key == null || payload == null) return
            handleCustomCommand(key, payload)
        }
    }

    /**
     * Re-register the same single callback immediately before traffic. If Hi Rokid
     * internally exposes a single custom-command callback slot, this also makes the
     * BuildGround callback the current owner without creating a second binder/session.
     */
    private fun rearmCustomCmdCallback(active: IMediaStreamService? = service): Boolean {
        val svc = active ?: return false
        return runCatching {
            runCatching { svc.unregisterCustomCmdCallback(customCmdCallback) }
            svc.registerCustomCmdCallback(customCmdCallback)
            true
        }.getOrDefault(false)
    }

    private fun transmitChallenge(pending: PendingChallenge, manualRetry: Boolean): Boolean {
        if (pendingChallenge?.generation != pending.generation) return false
        pending.attempts += 1
        val message = JSONObject()
            .put("type", "bridge_challenge")
            .put("protocol", PROTOCOL_VERSION)
            .put("nonce", pending.nonce)
            .put("host", "com.buildground.nexus")

        val sent = send(message)
        update(
            bridgeVerified = false,
            handshakePhase = if (sent) "TX_CHALLENGE" else "TX_FAILED",
            txCount = currentState.txCount + 1,
            nonceStatus = "PENDING ${shortNonce(pending.nonce)} · attempt ${pending.attempts}",
            message = when {
                sent && manualRetry -> "TX challenge retry #${pending.attempts}; single callback armed"
                sent -> "TX challenge #${pending.attempts}; awaiting RX bridge_ready"
                else -> "TX challenge #${pending.attempts} failed"
            },
        )
        return sent
    }

    private fun scheduleChallengeRetries(pending: PendingChallenge) {
        CHALLENGE_RETRY_DELAYS_MS.forEach { delayMs ->
            main.postDelayed({
                val active = pendingChallenge ?: return@postDelayed
                if (active.generation != pending.generation || currentState.bridgeVerified) return@postDelayed
                transmitChallenge(active, manualRetry = false)
            }, delayMs)
        }

        main.postDelayed({
            val active = pendingChallenge ?: return@postDelayed
            if (active.generation != pending.generation || currentState.bridgeVerified) return@postDelayed
            pendingChallenge = null
            update(
                bridgeVerified = false,
                handshakePhase = "RX_TIMEOUT",
                nonceStatus = "TIMEOUT ${shortNonce(active.nonce)}",
                message = "RX timeout: no matching bridge_ready received from glasses",
            )
        }, CHALLENGE_TIMEOUT_MS)
    }

    private fun handleCustomCommand(key: String, payload: ByteArray) {
        if (key != CHANNEL) return
        val rxCount = currentState.rxCount + 1
        if (payload.isEmpty()) {
            update(
                rxCount = rxCount,
                handshakePhase = "RX_EMPTY",
                message = "RX callback reached phone, but payload was empty",
            )
            return
        }

        val text = decode(payload)
        if (text.isBlank()) {
            update(
                rxCount = rxCount,
                handshakePhase = "RX_DECODE_FAILED",
                message = "RX callback reached phone, but payload decode failed",
            )
            return
        }

        val message = runCatching { JSONObject(text) }.getOrNull()
        if (message == null) {
            update(
                rxCount = rxCount,
                handshakePhase = "RX_JSON_FAILED",
                message = "RX callback reached phone, but JSON parse failed",
            )
            return
        }

        val type = message.optString("type", "unknown")
        val nonce = message.optString("nonce")
        if (message.optInt("protocol", -1) != PROTOCOL_VERSION) {
            update(
                rxCount = rxCount,
                handshakePhase = "RX_PROTOCOL_MISMATCH",
                message = "RX $type received with wrong protocol version",
            )
            return
        }

        val active = pendingChallenge
        if (active == null && nonce.isNotBlank() && nonce == lastVerifiedNonce && currentState.bridgeVerified) {
            update(
                rxCount = rxCount,
                handshakePhase = "VERIFIED",
                nonceStatus = "MATCH ${shortNonce(nonce)} · duplicate RX",
                message = "Duplicate verified reply received; Hardware Bridge remains VERIFIED",
            )
            return
        }

        val nonceMatched = active != null && nonce.isNotBlank() && nonce == active.nonce
        update(
            rxCount = rxCount,
            handshakePhase = "RX_${type.uppercase()}",
            nonceStatus = if (nonceMatched) {
                "MATCH ${shortNonce(nonce)}"
            } else {
                "MISMATCH rx=${shortNonce(nonce)} expected=${shortNonce(active?.nonce)}"
            },
            message = if (nonceMatched) "RX $type; nonce matched" else "RX $type; nonce mismatch",
        )
        if (!nonceMatched || active == null) return

        when (type) {
            "bridge_ready" -> {
                if (message.optString("companion") != GLASSES_PACKAGE) {
                    update(
                        handshakePhase = "RX_COMPANION_MISMATCH",
                        message = "RX bridge_ready matched nonce but companion identity was wrong",
                    )
                    return
                }
                pendingChallenge = null
                lastVerifiedNonce = nonce
                update(
                    bridgeVerified = true,
                    handshakePhase = "VERIFIED",
                    nonceStatus = "MATCH ${shortNonce(nonce)}",
                    message = "BUILDGROUND HARDWARE BRIDGE: VERIFIED",
                )
            }

            "bridge_pong" -> {
                pendingChallenge = null
                lastVerifiedNonce = nonce
                update(
                    bridgeVerified = true,
                    handshakePhase = "VERIFIED",
                    nonceStatus = "MATCH ${shortNonce(nonce)}",
                    message = "Hardware Bridge link OK",
                )
            }
        }
    }

    private fun schedulePostInstallQueries(generation: Long) {
        val delays = listOf(2_000L, 5_000L, 15_000L)
        delays.forEachIndexed { index, delayMs ->
            main.postDelayed({
                if (generation != installProbeGeneration) return@postDelayed
                val svc = service ?: return@postDelayed
                if (!currentState.glassesConnected) return@postDelayed

                val finalProbe = index == delays.lastIndex
                val callback = object : IGlassAppCallback.Stub() {
                    override fun onInstallAppResult(success: Boolean) = Unit
                    override fun onUnInstallAppResult(success: Boolean) = Unit
                    override fun onOpenAppResult(success: Boolean) = Unit
                    override fun onStopAppResult(success: Boolean) = Unit

                    override fun onQueryAppResult(pkg: String?, installed: Boolean) {
                        if (generation != installProbeGeneration) return
                        if (installed) {
                            installProbeGeneration += 1L
                            update(
                                companionInstalled = true,
                                companionOpened = false,
                                bridgeVerified = false,
                                handshakePhase = "INSTALL_CONFIRMED",
                                message = "BuildGround glasses companion installation CONFIRMED",
                            )
                            openCompanion()
                        } else if (finalProbe) {
                            update(
                                companionInstalled = false,
                                companionOpened = false,
                                bridgeVerified = false,
                                handshakePhase = "INSTALL_FAILED",
                                message = "BuildGround companion still not found after 15 s; Hi Rokid rejected or failed the APK installation",
                            )
                        } else {
                            update(message = "Waiting for BuildGround companion to appear on glasses…")
                        }
                    }
                }

                runCatching { svc.queryGlassAppInstalled(GLASSES_PACKAGE, callback) }
                    .onFailure {
                        if (finalProbe) update(message = "Could not confirm BuildGround companion installation")
                    }
            }, delayMs)
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
        if (!nativeCapsReady) return false
        val svc = service ?: return false
        if (!currentState.serviceConnected || !currentState.glassesConnected) return false

        return runCatching {
            rearmCustomCmdCallback(svc)
            val payload = Caps().apply { write(message.toString()) }.serialize()
            svc.sendCustomCmd(CHANNEL, payload) >= 0
        }.getOrDefault(false)
    }

    private fun decode(payload: ByteArray): String {
        val raw = runCatching { String(payload, Charsets.UTF_8).trim() }.getOrDefault("")
        if (raw.startsWith("{")) return raw
        return runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() > 0) caps.at(0).string else ""
        }.getOrDefault("")
    }

    private fun onServiceLost(message: String) {
        installProbeGeneration += 1L
        challengeGeneration += 1L
        service = null
        bound = false
        pendingChallenge = null
        lastVerifiedNonce = null
        update(
            serviceConnected = false,
            glassesConnected = false,
            customAppConnected = false,
            companionInstalled = false,
            companionOpened = false,
            bridgeVerified = false,
            handshakePhase = "SERVICE_LOST",
            nonceStatus = "—",
            message = message,
        )
    }

    private fun update(
        serviceConnected: Boolean = currentState.serviceConnected,
        glassesConnected: Boolean = currentState.glassesConnected,
        customAppConnected: Boolean = currentState.customAppConnected,
        companionInstalled: Boolean = currentState.companionInstalled,
        companionOpened: Boolean = currentState.companionOpened,
        bridgeVerified: Boolean = currentState.bridgeVerified,
        handshakePhase: String = currentState.handshakePhase,
        txCount: Int = currentState.txCount,
        rxCount: Int = currentState.rxCount,
        nonceStatus: String = currentState.nonceStatus,
        message: String = currentState.message,
    ) {
        currentState = State(
            serviceConnected = serviceConnected,
            glassesConnected = glassesConnected,
            customAppConnected = customAppConnected,
            companionInstalled = companionInstalled,
            companionOpened = companionOpened,
            bridgeVerified = bridgeVerified,
            handshakePhase = handshakePhase,
            txCount = txCount,
            rxCount = rxCount,
            nonceStatus = nonceStatus,
            message = message,
        )
        publish()
    }

    private fun publish() {
        val snapshot = currentState
        main.post { listener.onState(snapshot) }
    }

    private fun shortNonce(nonce: String?): String = when {
        nonce.isNullOrBlank() -> "—"
        nonce.length <= 8 -> nonce
        else -> nonce.take(8)
    }

    private companion object {
        const val MEDIA_STREAM_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        const val EXTRA_AUTH_PACKAGE = "auth_package"
        const val GLASSES_PACKAGE = "com.buildground.nexus.glasses"
        const val CHANNEL = "buildground.nexus.control.v1"
        const val PROTOCOL_VERSION = 1
        const val CHALLENGE_TIMEOUT_MS = 9_000L
        val CHALLENGE_RETRY_DELAYS_MS = longArrayOf(1_200L, 3_000L, 6_000L)
    }
}
