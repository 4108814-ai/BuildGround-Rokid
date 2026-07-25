package com.anezium.rokidbus.phone.mediasync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.MediaSyncLinkOffer
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class MediaSyncLinkBlocker {
    PHONE_WIFI_OFF,
    MISSING_PERMISSION,
    NO_P2P_SERVICE,
}

/**
 * Joins the glasses' media-sync Wi-Fi Direct group and runs one transfer session.
 *
 * Join is by credentials, never by discovery: the glasses run an autonomous group owner, which
 * `requestPeers` cannot see. One discovery scan per cycle exists purely to warm the supplicant's
 * scan cache. Every number here is copied from the camera link rather than retuned.
 *
 * If the phone's Wi-Fi is off the client refuses instead of toggling it — photo sync never
 * changes the wearer's radio state behind their back; the next trigger retries.
 */
internal class MediaSyncLinkClient(
    context: Context,
    private val ledger: SyncLedger,
    private val gallery: MediaSyncGalleryWriter,
    private val logger: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-sync-link").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val retryPolicy = MediaSyncJoinRetryPolicy()
    private val recoveryPolicy = MediaSyncJoinRecoveryPolicy()
    private val primingPolicy = MediaSyncDiscoveryPrimingPolicy()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var consecutiveFailures = 0
    private var primedForCycle = false

    @Volatile private var active: Attempt? = null

    private class Attempt(
        val generation: Int,
        val offer: MediaSyncLinkOffer,
        val deleteAfterSync: Boolean,
        val onProgress: (MediaSyncProgress) -> Unit,
        val onDeletionOutcome: (Boolean) -> Unit,
        val onFinished: (MediaSyncRun) -> Unit,
    ) {
        @Volatile
        var socketOpened = false
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val attempt = active ?: return
            requestConnectionInfo(attempt)
        }
    }

    /** Returns the blocker that stopped the attempt from even starting, or null once under way. */
    fun start(
        offer: MediaSyncLinkOffer,
        deleteAfterSync: Boolean,
        onProgress: (MediaSyncProgress) -> Unit,
        onDeletionOutcome: (Boolean) -> Unit,
        onFinished: (MediaSyncRun) -> Unit,
    ): MediaSyncLinkBlocker? {
        if (closed.get()) return MediaSyncLinkBlocker.NO_P2P_SERVICE
        if (!hasNearbyWifiPermission()) return MediaSyncLinkBlocker.MISSING_PERMISSION
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled != true) return MediaSyncLinkBlocker.PHONE_WIFI_OFF
        val p2pManager = appContext
            .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return MediaSyncLinkBlocker.NO_P2P_SERVICE
        manager = p2pManager
        val p2pChannel = channel ?: p2pManager.initialize(appContext, Looper.getMainLooper()) {
            logger("mediaSync link channel disconnected")
            channel = null
        } ?: return MediaSyncLinkBlocker.NO_P2P_SERVICE
        channel = p2pChannel
        registerReceiver()
        retryPolicy.reset()
        consecutiveFailures = 0
        primedForCycle = false
        val attempt = Attempt(
            generation = generation.incrementAndGet(),
            offer = offer,
            deleteAfterSync = deleteAfterSync,
            onProgress = onProgress,
            onDeletionOutcome = onDeletionOutcome,
            onFinished = onFinished,
        )
        active = attempt
        mainHandler.post { primeThenConnect(attempt) }
        return null
    }

    private fun primeThenConnect(attempt: Attempt) {
        if (!isCurrent(attempt)) return
        val decision = primingPolicy.decision(primedForCycle)
        if (!decision.shouldPrime) {
            connectByCredentials(attempt)
            return
        }
        primedForCycle = true
        val manager = manager ?: return finish(attempt, failure("No Wi-Fi Direct service"))
        val channel = channel ?: return finish(attempt, failure("No Wi-Fi Direct channel"))
        // Exactly one join per priming cycle. Three paths can reach the join below — the
        // stop-discovery success callback, its failure callback, and the fallback timer — and a
        // second connect() landing inside a healthy join is what produces a BUSY error, an
        // inflated failure count, and a recovery removeGroup that kills the working join.
        val dispatch = MediaSyncSingleDispatch()
        val join = {
            if (isCurrent(attempt) && !attempt.socketOpened && dispatch.claim()) {
                connectByCredentials(attempt)
            }
        }
        runCatching {
            manager.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                },
            )
        }
        mainHandler.postDelayed(
            {
                runCatching {
                    manager.stopPeerDiscovery(
                        channel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = join()
                            override fun onFailure(reason: Int) = join()
                        },
                    )
                }.onFailure { join() }
                // The framework sometimes swallows the stop callback entirely, so the cycle needs
                // a timer that can still start the join. The claim above makes it a no-op in the
                // normal case where the callback did arrive.
                mainHandler.postDelayed(join, decision.stopCallbackFallbackMs)
            },
            decision.discoveryWaitMs,
        )
    }

    private fun connectByCredentials(attempt: Attempt) {
        if (!isCurrent(attempt) || attempt.socketOpened) return
        val manager = manager ?: return finish(attempt, failure("No Wi-Fi Direct service"))
        val channel = channel ?: return finish(attempt, failure("No Wi-Fi Direct channel"))
        val number = retryPolicy.startAttempt()
        if (number == null) {
            finish(attempt, failure("Could not join the glasses"))
            return
        }
        logger("mediaSync link join attempt=$number ssid=${attempt.offer.ssid}")
        // enablePersistentMode(false) is the whole persistent-profile hygiene story on the phone
        // side: the framework never stores a profile for this join, so nothing accumulates and no
        // janitor is needed. (The hidden `netId` reflection the camera work explored is blocked by
        // hiddenapi at targetSdk 36 and buys nothing on top of this.)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(attempt.offer.ssid)
            .setPassphrase(attempt.offer.passphrase)
            .enablePersistentMode(false)
            .build()
        runCatching {
            manager.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        mainHandler.postDelayed(
                            { requestConnectionInfo(attempt) },
                            CONNECTION_INFO_POLL_MS,
                        )
                    }

                    override fun onFailure(reason: Int) = onJoinFailure(attempt, "connect_$reason")
                },
            )
        }.onFailure { onJoinFailure(attempt, "connect_threw") }
        mainHandler.postDelayed(
            { if (isCurrent(attempt) && !attempt.socketOpened) onJoinFailure(attempt, "join_timeout") },
            JOIN_PROGRESS_TIMEOUT_MS,
        )
    }

    private fun requestConnectionInfo(attempt: Attempt) {
        if (!isCurrent(attempt) || attempt.socketOpened) return
        val manager = manager ?: return
        val channel = channel ?: return
        runCatching {
            manager.requestConnectionInfo(channel) { info ->
                if (!isCurrent(attempt) || attempt.socketOpened) return@requestConnectionInfo
                if (info == null || !info.groupFormed) {
                    mainHandler.postDelayed(
                        { requestConnectionInfo(attempt) },
                        CONNECTION_INFO_POLL_MS,
                    )
                    return@requestConnectionInfo
                }
                val host = info.groupOwnerAddress?.hostAddress?.takeIf { it.isNotBlank() }
                    ?: attempt.offer.goIp
                attempt.socketOpened = true
                executor.execute { runSession(attempt, host) }
            }
        }
    }

    private fun onJoinFailure(attempt: Attempt, reason: String) {
        if (!isCurrent(attempt) || attempt.socketOpened) return
        consecutiveFailures += 1
        logger("mediaSync link join failed reason=$reason failures=$consecutiveFailures")
        when (recoveryPolicy.actionAfter(consecutiveFailures)) {
            MediaSyncJoinRecoveryAction.REMOVE_GROUP -> removeGroup { retryJoin(attempt) }
            MediaSyncJoinRecoveryAction.RESET_CHANNEL -> {
                closeChannel()
                val manager = appContext
                    .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                this.manager = manager
                channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
                retryJoin(attempt)
            }
            MediaSyncJoinRecoveryAction.NONE -> retryJoin(attempt)
        }
    }

    private fun retryJoin(attempt: Attempt) {
        if (!isCurrent(attempt)) return
        val delay = retryPolicy.retryDelayAfter(consecutiveFailures)
        if (delay == null) {
            finish(attempt, failure("Could not join the glasses"))
            return
        }
        primedForCycle = false
        mainHandler.postDelayed({ primeThenConnect(attempt) }, delay)
    }

    private fun runSession(attempt: Attempt, host: String) {
        val socket = runCatching {
            Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(host, attempt.offer.port), CONNECT_TIMEOUT_MS)
            }
        }.onFailure { logger("mediaSync link connect failed host=$host error=${it.message}") }
            .getOrNull()
        if (socket == null) {
            mainHandler.post { onJoinFailure(attempt, "tcp_connect_failed") }
            return
        }
        val run = runCatching {
            MediaSyncTransferSession(
                socket = socket,
                token = attempt.offer.token,
                ledger = ledger,
                gallery = gallery,
                deleteAfterSync = attempt.deleteAfterSync,
                clock = clock,
                logger = logger,
                onProgress = attempt.onProgress,
                onDeletionOutcome = attempt.onDeletionOutcome,
            ).run()
        }.onFailure { logger("mediaSync session failed error=${it.message}") }
            .getOrElse { failure("Transfer interrupted") }
        runCatching { socket.close() }
        finish(attempt, run)
    }

    private fun finish(attempt: Attempt, run: MediaSyncRun) {
        if (active !== attempt) return
        active = null
        teardown()
        runCatching { attempt.onFinished(run) }
            .onFailure { logger("mediaSync link completion failed error=${it.message}") }
    }

    private fun failure(message: String) = MediaSyncRun(
        finishedAtMillis = clock(),
        result = MediaSyncResult.FAILED,
        filesSynced = 0,
        bytesSynced = 0L,
        filesFailed = 0,
        filesDeleted = 0,
        message = message,
    )

    private fun teardown() {
        mainHandler.post {
            removeGroup { }
        }
    }

    private fun removeGroup(onComplete: () -> Unit) {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            onComplete()
            return
        }
        var settled = false
        val finishOnce = {
            if (!settled) {
                settled = true
                onComplete()
            }
        }
        runCatching {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = finishOnce()
                    override fun onFailure(reason: Int) = finishOnce()
                },
            )
        }.onFailure { finishOnce() }
        mainHandler.postDelayed({ finishOnce() }, GROUP_REMOVE_CALLBACK_TIMEOUT_MS)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(p2pReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { logger("mediaSync link receiver failed error=${it.message}") }
    }

    private fun closeChannel() {
        runCatching { channel?.close() }
        channel = null
    }

    private fun hasNearbyWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        !closed.get() && active === attempt && attempt.generation == generation.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active = null
        mainHandler.post {
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(p2pReceiver) }
                receiverRegistered = false
            }
            removeGroup { closeChannel() }
        }
        executor.shutdownNow()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 45_000
        const val CONNECTION_INFO_POLL_MS = 500L
        const val JOIN_PROGRESS_TIMEOUT_MS = 4_500L
        const val GROUP_REMOVE_CALLBACK_TIMEOUT_MS = 1_000L
    }
}
