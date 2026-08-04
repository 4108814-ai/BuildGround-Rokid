package com.anezium.rokidbus.glasses

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

internal object AccessibilityRearmWatcher {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WATCHDOG_RETRY_DELAY_MS = 1_500L
    private val registered = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val watchdogRetryPolicy = SelfArmWatchdogRetryPolicy()
    private val bridgeLivenessPolicy = SelfArmBridgeLivenessPolicy(SystemClock::elapsedRealtime)

    /** Last observed wireless-debugging state, so only a genuine off-to-on edge acts. */
    @Volatile private var adbWifiObservedEnabled = false
    private var retryContext: Context? = null
    private var retrySignalReason = "reachability"
    private val watchdogRetryRunnable = Runnable {
        val context = retryContext ?: return@Runnable
        if (!watchdogRetryPolicy.onRetryDelayElapsed()) return@Runnable
        val reason = retrySignalReason
        log("Watchdog re-arm retry starting trigger=$reason")
        val started = requestWatchdogEnsure(
            context = context,
            reason = "watchdog_reachability:$reason",
        )
        if (!started) {
            watchdogRetryPolicy.onRetryStartRejected()
            SelfArmController.runWhenIdle {
                signalWatchdogReachability(context, "self_arm_idle")
            }
        }
    }

    fun start(context: Context, reason: String) {
        val appContext = context.applicationContext
        registerEventListeners(appContext)
        // The verdict, not an extra attempt: the unconditional ensure below already revives the
        // bridge whenever the privileged session is reachable. What was missing after a reboot is
        // the log line saying the bridge cannot have survived it.
        logBridgeLivenessVerdict(appContext, "start:$reason")
        ensureWatchdog(appContext, reason)
        repairIfNeeded(appContext, "$reason:initial_state")
    }

    fun ensureWatchdog(context: Context, reason: String, onComplete: (() -> Unit)? = null) {
        if (!requestWatchdogEnsure(context.applicationContext, reason, onComplete = onComplete)) {
            onComplete?.invoke()
        }
    }

    /** The owner reaching for setup is fresh intent; a spent liveness budget must not survive it. */
    fun onSetupScreenOpened() {
        bridgeLivenessPolicy.reset()
        log("Bridge liveness attempts reset reason=setup_screen_opened")
    }

    private fun registerEventListeners(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager.addAccessibilityStateChangeListener { enabled ->
            log("Accessibility state changed enabled=$enabled")
            repairIfNeeded(context, "accessibility_manager")
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    repairIfNeeded(context, "enabled_services_setting")
                }
            },
        )
        adbWifiObservedEnabled = SelfArmWirelessAdbController.isEnabled(context)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_WIFI_ENABLED),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val enabled = SelfArmWirelessAdbController.isEnabled(context)
                    // Rising edge only: with Wi-Fi off the framework reverts this setting to 0,
                    // and reacting to anything but "it just became 1" would turn that revert plus
                    // our own maintenance re-enable into a re-arm loop.
                    val becameEnabled = enabled && !adbWifiObservedEnabled
                    adbWifiObservedEnabled = enabled
                    log("Wireless debugging setting changed enabled=$enabled edge=$becameEnabled")
                    if (!becameEnabled) return
                    bridgeLivenessPolicy.reset()
                    signalWatchdogReachability(context, "adb_wifi_enabled")
                }
            },
        )
        runCatching {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // A network the unit just gained is a fresh opportunity: it replenishes
                        // the liveness budget before the signal consults it.
                        bridgeLivenessPolicy.reset()
                        signalWatchdogReachability(context, "wifi_network_available")
                    }
                },
            )
        }.onFailure {
            logError("Watchdog Wi-Fi network callback registration failed", it)
        }
        log("Accessibility and watchdog re-arm event listeners registered")
    }

    private fun requestWatchdogEnsure(
        context: Context,
        reason: String,
        restartWatchdog: Boolean = true,
        onComplete: (() -> Unit)? = null,
    ): Boolean = SelfArmController.ensureWatchdog(context, reason, restartWatchdog) { result ->
        handler.post {
            handler.removeCallbacks(watchdogRetryRunnable)
            val shouldSchedule = watchdogRetryPolicy.onEnsureFinished(result)
            // A successful arm just started the bridge or confirmed it at the current version, so
            // earlier liveness attempts no longer describe anything.
            if (result == SelfArmWatchdogEnsureResult.READY) bridgeLivenessPolicy.reset()
            log("Watchdog ensure finished reason=$reason result=$result retryScheduled=$shouldSchedule")
            if (shouldSchedule) postWatchdogRetry(context, "deferred_reachability")
            onComplete?.invoke()
        }
    }

    private fun signalWatchdogReachability(context: Context, reason: String) {
        handler.post {
            val shouldSchedule = watchdogRetryPolicy.onReachabilitySignal()
            log("Watchdog reachability signal reason=$reason retryScheduled=$shouldSchedule")
            if (shouldSchedule) {
                postWatchdogRetry(context, reason)
            } else {
                // The retry policy only re-runs arms that failed; a bridge that died behind a
                // successful arm is invisible to it. Reachability signals are the only other place
                // a radio opportunity surfaces, so liveness gets its look here.
                maybeRearmBridge(context, reason)
            }
        }
    }

    private fun maybeRearmBridge(context: Context, trigger: String) {
        val cause = when {
            SelfArmBridgeLivenessStore.presumedDead(context) -> "presumed_dead"
            SelfArmBridgeLivenessStore.isBridgeDemandPending() -> "bridge_demand"
            else -> return
        }
        val blocker = bridgeRearmBlocker(context)
        if (blocker != null) {
            log("Bridge re-arm skipped trigger=$trigger cause=$cause blockedBy=$blocker")
            return
        }
        when (val verdict = bridgeLivenessPolicy.claimAttempt()) {
            is SelfArmBridgeLivenessPolicy.Verdict.Backoff ->
                log(
                    "Bridge re-arm deferred trigger=$trigger cause=$cause " +
                        "remainingMs=${verdict.remainingMs}",
                )
            SelfArmBridgeLivenessPolicy.Verdict.CapExhausted ->
                log("Bridge re-arm skipped trigger=$trigger cause=$cause blockedBy=attempt_cap")
            SelfArmBridgeLivenessPolicy.Verdict.Attempt -> {
                log("Bridge re-arm starting trigger=$trigger cause=$cause")
                val started = requestWatchdogEnsure(
                    context = context,
                    reason = "${SelfArmController.BRIDGE_LIVENESS_REASON_PREFIX}$trigger",
                    restartWatchdog = false,
                )
                if (!started) {
                    bridgeLivenessPolicy.onAttemptRejected()
                    log("Bridge re-arm coalesced trigger=$trigger: self-arm already running")
                }
            }
        }
    }

    /**
     * A liveness re-arm talks over the same radio and privileged session everything else uses, so
     * it yields to whoever owns them right now. Names the first blocker so the log says why
     * nothing happened.
     */
    private fun bridgeRearmBlocker(context: Context): String? = when {
        !SelfArmLocalAdbBootstrapper.isBootstrapComplete(context) -> "bootstrap_incomplete"
        !SelfArmOnboardingStore.isWifiReady(context) -> "wifi_not_ready"
        SelfArmOnboardingStore.currentActiveSessionId(context).isNotBlank() -> "setup_session_active"
        MediaSyncEngine.isSessionActive() -> "media_sync_session_active"
        GlassesHub.isWifiHubOwned() -> "wifi_hub_owned"
        else -> null
    }

    private fun logBridgeLivenessVerdict(context: Context, trigger: String) {
        val armedBootInstant = SelfArmBridgeLivenessStore.armedBootInstantMillis(context)
        val bootInstant = SelfArmBridgeLivenessStore.currentBootInstantMillis()
        val presumedDead = SelfArmBridgeLivenessPolicy.presumedDead(armedBootInstant, bootInstant)
        log(
            "Bridge liveness trigger=$trigger presumedDead=$presumedDead " +
                "armedBootInstant=${armedBootInstant ?: "none"} bootInstant=$bootInstant " +
                "demandPending=${SelfArmBridgeLivenessStore.isBridgeDemandPending()}",
        )
    }

    private fun postWatchdogRetry(context: Context, reason: String) {
        retryContext = context.applicationContext
        retrySignalReason = reason
        handler.removeCallbacks(watchdogRetryRunnable)
        handler.postDelayed(watchdogRetryRunnable, WATCHDOG_RETRY_DELAY_MS)
    }

    private fun repairIfNeeded(context: Context, reason: String) {
        // Decide on the raw secure settings, not AccessibilityManager. Its cached enabled-service
        // list lags the setting write that triggers our ContentObserver, so gating on it here
        // silently dropped legitimate repairs. SelfArmController.repairNow re-reads the setting and
        // no-ops when nothing is wrong, so delegating unconditionally is both correct and cheap.
        SelfArmController.repairNow(context, reason)
    }
}
