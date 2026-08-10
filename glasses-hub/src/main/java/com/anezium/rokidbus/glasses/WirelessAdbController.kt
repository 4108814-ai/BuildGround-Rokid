package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.anezium.rokidbus.shared.WirelessAdbAction
import com.anezium.rokidbus.shared.WirelessAdbReply
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object WirelessAdbController {
    private data class PairingSession(
        val serviceName: String,
        val expiresAtMillis: Long,
    )

    private data class PairingEndpoint(val port: Int)

    private val random = SecureRandom()
    private val pairingLock = Any()
    private val expiryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NexusAdbPairingExpiry").apply { isDaemon = true }
    }
    private var activePairing: PairingSession? = null

    fun restorePairingExpiry(context: Context) {
        val preferences = context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
        val serviceName = preferences.getString(KEY_SERVICE_NAME, null).orEmpty()
        val expiresAtMillis = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (serviceName.isBlank() || expiresAtMillis <= 0L) return
        val session = PairingSession(serviceName, expiresAtMillis)
        synchronized(pairingLock) { activePairing = session }
        scheduleExpiry(context.applicationContext, session)
    }

    fun handle(context: Context, action: WirelessAdbAction): WirelessAdbReply = when (action) {
        WirelessAdbAction.STATUS -> status(context, action, success = true)
        WirelessAdbAction.ENABLE -> enable(context, action)
        WirelessAdbAction.START_PAIRING -> startPairing(context)
        WirelessAdbAction.CANCEL_PAIRING -> cancelPairing(context, action)
        WirelessAdbAction.DISABLE -> disable(context)
    }

    private fun enable(context: Context, action: WirelessAdbAction): WirelessAdbReply {
        preconditionFailure(context, action)?.let { return it }
        val enabled = SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, true)
        return if (enabled) {
            status(context, action, success = true)
        } else {
            failure(
                context,
                action,
                "PRIVILEGED_BRIDGE_UNAVAILABLE",
                "Nexus could not enable wireless debugging. Repair the glasses helper and try again.",
            )
        }
    }

    private fun startPairing(context: Context): WirelessAdbReply {
        val action = WirelessAdbAction.START_PAIRING
        preconditionFailure(context, action)?.let { return it }
        if (!SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, true)) {
            return failure(
                context,
                action,
                "PRIVILEGED_BRIDGE_UNAVAILABLE",
                "Nexus could not enable wireless debugging. Repair the glasses helper and try again.",
            )
        }
        val host = localIpv4Address(context) ?: return failure(
            context,
            action,
            "NO_IPV4_ADDRESS",
            "The glasses do not have a usable IPv4 address on this Wi-Fi network.",
        )
        cancelPairingInternal(context)
        val serviceName = "Nexus_${randomHex(8)}"
        val pairingCode = (random.nextInt(900_000) + 100_000).toString()
        val endpoint = discoverPairingEndpoint(context, serviceName) {
            WirelessAdbShell.startPairing(context, serviceName, pairingCode).success
        }
        if (endpoint == null) {
            WirelessAdbShell.stopPairing(context)
            return failure(
                context,
                action,
                "PAIRING_SERVICE_NOT_FOUND",
                "The pairing service did not become visible on the local network.",
            )
        }
        val connectPort = SelfArmWirelessAdbController.readWirelessPort()
        if (connectPort <= 0) {
            WirelessAdbShell.stopPairing(context)
            return failure(
                context,
                action,
                "WIRELESS_DEBUGGING_STOPPED",
                "Wireless debugging stopped before pairing was ready.",
            )
        }
        val expiresAtMillis = System.currentTimeMillis() + PAIRING_LIFETIME_MS
        val session = PairingSession(serviceName, expiresAtMillis)
        synchronized(pairingLock) { activePairing = session }
        context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE_NAME, serviceName)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
        scheduleExpiry(context.applicationContext, session)
        return WirelessAdbReply(
            action = action,
            success = true,
            wifiConnected = true,
            enabled = true,
            pairingActive = true,
            host = host,
            connectPort = connectPort,
            pairingPort = endpoint.port,
            pairingCode = pairingCode,
            expiresAtMillis = expiresAtMillis,
        )
    }

    private fun scheduleExpiry(context: Context, session: PairingSession) {
        val delayMillis = (session.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        expiryExecutor.schedule(
            {
                val shouldStop = synchronized(pairingLock) {
                    if (activePairing?.serviceName == session.serviceName) {
                        activePairing = null
                        true
                    } else {
                        false
                    }
                }
                if (shouldStop) {
                    clearStoredPairing(context)
                    WirelessAdbShell.stopPairing(context)
                    Log.i(TAG, "temporary ADB pairing window expired")
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPairing(context: Context, action: WirelessAdbAction): WirelessAdbReply {
        val stopped = cancelPairingInternal(context)
        return if (stopped || SelfArmWirelessAdbController.readWirelessPort() <= 0) {
            status(context, action, success = true)
        } else {
            failure(context, action, "PAIRING_CANCEL_FAILED", "The temporary pairing window could not be closed.")
        }
    }

    private fun disable(context: Context): WirelessAdbReply {
        cancelPairingInternal(context)
        val disabled = SelfArmCommandBridgeClient.setWirelessAdbEnabled(context, false)
        return if (disabled) {
            status(context, WirelessAdbAction.DISABLE, success = true)
        } else {
            failure(
                context,
                WirelessAdbAction.DISABLE,
                "DISABLE_FAILED",
                "Wireless debugging could not be disabled.",
            )
        }
    }

    private fun cancelPairingInternal(context: Context): Boolean {
        synchronized(pairingLock) { activePairing = null }
        clearStoredPairing(context)
        return WirelessAdbShell.stopPairing(context).success
    }

    private fun clearStoredPairing(context: Context) {
        context.getSharedPreferences(PAIRING_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SERVICE_NAME)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun preconditionFailure(context: Context, action: WirelessAdbAction): WirelessAdbReply? {
        if (!WirelessAdbShell.supportsApiLevel(Build.VERSION.SDK_INT)) {
            return failure(
                context,
                action,
                "UNSUPPORTED_ANDROID_VERSION",
                "This glasses firmware is not supported for automatic ADB pairing.",
            )
        }
        if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(context)) {
            return failure(
                context,
                action,
                "DEVELOPER_OPTIONS_DISABLED",
                "Developer options are not enabled on the glasses.",
            )
        }
        if (!SelfArmWirelessAdbController.isWifiNetworkReady(context)) {
            return failure(
                context,
                action,
                "WIFI_REQUIRED",
                "Connect the glasses to Wi-Fi before enabling wireless debugging.",
            )
        }
        return null
    }

    private fun status(
        context: Context,
        action: WirelessAdbAction,
        success: Boolean,
    ): WirelessAdbReply {
        val wifiConnected = SelfArmWirelessAdbController.isWifiNetworkReady(context)
        val connectPort = SelfArmWirelessAdbController.readWirelessPort().takeIf { it > 0 }
        val pairingActive = synchronized(pairingLock) {
            activePairing?.expiresAtMillis?.let { it > System.currentTimeMillis() } == true
        }
        return WirelessAdbReply(
            action = action,
            success = success,
            wifiConnected = wifiConnected,
            enabled = connectPort != null && SelfArmWirelessAdbController.isEnabled(context),
            pairingActive = pairingActive,
            host = if (connectPort != null) localIpv4Address(context) else null,
            connectPort = connectPort,
        )
    }

    private fun failure(
        context: Context,
        action: WirelessAdbAction,
        code: String,
        message: String,
    ): WirelessAdbReply = status(context, action, success = false).copy(
        errorCode = code,
        message = message,
    )

    private fun localIpv4Address(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun discoverPairingEndpoint(
        context: Context,
        expectedServiceName: String,
        startPairing: () -> Boolean,
    ): PairingEndpoint? {
        val manager = context.getSystemService(NsdManager::class.java) ?: return null
        val localAddresses = localNetworkAddresses(context)
        if (localAddresses.isEmpty()) return null
        val discoveryReady = CountDownLatch(1)
        val discoveryStarted = AtomicBoolean(false)
        val endpointReady = CountDownLatch(1)
        val endpoint = AtomicReference<PairingEndpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted.set(true)
                discoveryReady.countDown()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                discoveryReady.countDown()

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val discoveredName = serviceInfo.serviceName
                if (discoveredName != expectedServiceName &&
                    !discoveredName.startsWith("$expectedServiceName (")
                ) {
                    return
                }
                @Suppress("DEPRECATION")
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val resolvedAddress = serviceInfo.host
                                ?.hostAddress
                                ?.substringBefore('%')
                            serviceInfo.port.takeIf {
                                it in 1..65535 &&
                                    resolvedAddress != null &&
                                    resolvedAddress in localAddresses
                            }?.let { port ->
                                endpoint.compareAndSet(null, PairingEndpoint(port))
                                endpointReady.countDown()
                            }
                        }
                    },
                )
            }
        }
        return try {
            manager.discoverServices(PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            if (!discoveryReady.await(DISCOVERY_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            if (!discoveryStarted.get()) return null
            if (!startPairing()) return null
            endpointReady.await(PAIRING_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            endpoint.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (failure: Exception) {
            Log.i(TAG, "ADB pairing discovery failed: ${failure.javaClass.simpleName}")
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private fun localNetworkAddresses(context: Context): Set<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching emptySet()
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.mapNotNull { link ->
                link.address
                    ?.takeIf { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?.substringBefore('%')
            }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount)
        .also(random::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val TAG = "NexusWirelessAdb"
    private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val DISCOVERY_START_TIMEOUT_MS = 2_000L
    private const val PAIRING_DISCOVERY_TIMEOUT_MS = 12_000L
    private const val PAIRING_LIFETIME_MS = 2L * 60L * 1_000L
    private const val PAIRING_PREFERENCES = "wireless_adb_pairing"
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_EXPIRES_AT = "expires_at"
}
