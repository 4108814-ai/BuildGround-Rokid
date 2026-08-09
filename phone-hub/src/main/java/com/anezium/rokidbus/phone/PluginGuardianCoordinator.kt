package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

internal data class PluginGuardianTarget(
    val grantKey: PluginGrantKey,
    val component: ComponentName,
)

internal fun selectApprovedGuardianTargets(
    principals: List<PhonePluginPrincipal>,
    grantState: (PhonePluginPrincipal) -> PluginGrantState,
): List<PluginGuardianTarget> = principals.mapNotNull { principal ->
    val component = principal.guardianServiceComponent ?: return@mapNotNull null
    if (grantState(principal) !is PluginGrantState.Approved) return@mapNotNull null
    PluginGuardianTarget(principal.grantKey(), component)
}

internal class PluginGuardianCoordinator(
    private val context: Context,
    private val targetProvider: () -> List<PluginGuardianTarget>,
    private val logger: (String) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
    stoppedFlagReader: ((String) -> Boolean?)? = null,
) {
    private data class BindingState(
        var target: PluginGuardianTarget,
        var connection: ServiceConnection? = null,
        var retry: Runnable? = null,
        var failureCount: Int = 0,
    )

    private val lifetimePolicy = GuardianBindLifetimePolicy()
    private val bindings = linkedMapOf<PluginGrantKey, BindingState>()
    private val stoppedFlagReader = stoppedFlagReader ?: { packageName ->
        readStoppedFlag(context.packageManager, packageName)
    }
    private val releaseBindings = Runnable(::onReleaseTimer)
    @Volatile private var closed = false

    fun onLinkStateChanged(linkUp: Boolean) {
        dispatch {
            when (val decision = lifetimePolicy.onLinkStateChanged(linkUp, nowMillis())) {
                GuardianLinkDecision.EnsureBound -> {
                    handler.removeCallbacks(releaseBindings)
                    reconcileTargets(allowNewBindings = true)
                }
                is GuardianLinkDecision.ScheduleRelease -> {
                    scheduleRelease(decision.delayMillis)
                    reconcileTargets(allowNewBindings = false)
                }
                GuardianLinkDecision.Release -> releaseAll("link_linger_expired")
                GuardianLinkDecision.None -> reconcileTargets(allowNewBindings = false)
            }
        }
    }

    fun refreshEligibility() {
        dispatch {
            reconcileTargets(allowNewBindings = lifetimePolicy.isLinkUp)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (Looper.myLooper() == handler.looper) {
            closeOnHandler()
        } else {
            handler.post(::closeOnHandler)
        }
    }

    private fun closeOnHandler() {
        handler.removeCallbacks(releaseBindings)
        releaseAll("coordinator_closed")
    }

    private fun dispatch(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == handler.looper) {
            action()
        } else {
            handler.post {
                if (!closed) action()
            }
        }
    }

    private fun onReleaseTimer() {
        if (closed) return
        when (val decision = lifetimePolicy.onReleaseTimer(nowMillis())) {
            is GuardianLinkDecision.ScheduleRelease -> scheduleRelease(decision.delayMillis)
            GuardianLinkDecision.Release -> releaseAll("link_linger_expired")
            GuardianLinkDecision.EnsureBound,
            GuardianLinkDecision.None -> Unit
        }
    }

    private fun scheduleRelease(delayMillis: Long) {
        handler.removeCallbacks(releaseBindings)
        handler.postDelayed(releaseBindings, delayMillis)
    }

    private fun reconcileTargets(allowNewBindings: Boolean) {
        val eligibleTargets = loadEligibleTargets() ?: return
        bindings.toMap().forEach { (key, state) ->
            val current = eligibleTargets[key]
            if (current == null || current.component != state.target.component) {
                releaseState(key, state, "no_longer_eligible")
            } else {
                state.target = current
            }
        }
        if (!allowNewBindings) return
        eligibleTargets.forEach { (key, target) ->
            if (key in bindings) return@forEach
            val state = BindingState(target)
            bindings[key] = state
            attemptBind(key, state)
        }
    }

    private fun loadEligibleTargets(): Map<PluginGrantKey, PluginGuardianTarget>? =
        runCatching { targetProvider() }
            .onFailure { failure ->
                logger("plugin guardian discovery failed error=${failure.javaClass.simpleName}")
            }
            .getOrNull()
            ?.filter { target -> target.component.packageName == target.grantKey.packageName }
            ?.associateBy(PluginGuardianTarget::grantKey)

    private fun attemptBind(key: PluginGrantKey, state: BindingState) {
        if (closed || bindings[key] !== state || state.connection != null) return
        val eligibleTargets = loadEligibleTargets()
        if (eligibleTargets == null) {
            scheduleRetry(key, state, "eligibility_unavailable")
            return
        }
        val current = eligibleTargets[key]
        if (current == null || current.component != state.target.component) {
            releaseState(key, state, "no_longer_eligible")
            if (lifetimePolicy.isLinkUp) reconcileTargets(allowNewBindings = true)
            return
        }
        state.target = current
        val connection = GuardianConnection(key, state)
        val intent = Intent().setComponent(current.component)
        state.connection = connection
        val stoppedBeforeBind = runCatching {
            stoppedFlagReader(current.component.packageName)
        }.getOrNull()
        logger(
            "plugin guardian bind attempt plugin=${key.pluginId} " +
                "stoppedBeforeBind=${stoppedBeforeBind?.toString() ?: "unknown"} " +
                "failureCount=${state.failureCount}",
        )
        val bound = runCatching {
            context.bindService(
                intent,
                connection,
                // This is the same foreground-importance propagation used by live plugin sessions.
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
        }.onFailure { failure ->
            logger(
                "plugin guardian bind failed plugin=${key.pluginId} " +
                    "error=${failure.javaClass.simpleName}",
            )
        }.getOrDefault(false)
        if (bound) {
            logger("plugin guardian bind accepted plugin=${key.pluginId}")
            return
        }
        if (state.connection === connection) state.connection = null
        scheduleRetry(key, state, "bind_rejected")
    }

    private fun scheduleRetry(key: PluginGrantKey, state: BindingState, reason: String) {
        if (closed || bindings[key] !== state) return
        state.retry?.let(handler::removeCallbacks)
        if (state.failureCount < Int.MAX_VALUE) state.failureCount += 1
        val delayMillis = GuardianBindRetryPolicy.delayMillis(state.failureCount)
        val retry = Runnable {
            state.retry = null
            if (!closed && bindings[key] === state) attemptBind(key, state)
        }
        state.retry = retry
        handler.postDelayed(retry, delayMillis)
        logger(
            "plugin guardian retry scheduled plugin=${key.pluginId} " +
                "reason=$reason delayMs=$delayMillis failureCount=${state.failureCount}",
        )
    }

    private fun handleDeadBinding(
        key: PluginGrantKey,
        state: BindingState,
        connection: ServiceConnection,
        reason: String,
    ) {
        if (closed || bindings[key] !== state || state.connection !== connection) return
        state.connection = null
        runCatching { context.unbindService(connection) }
        scheduleRetry(key, state, reason)
    }

    private fun releaseState(key: PluginGrantKey, state: BindingState, reason: String) {
        if (bindings[key] !== state) return
        bindings.remove(key)
        state.retry?.let(handler::removeCallbacks)
        state.retry = null
        state.connection?.let { connection ->
            state.connection = null
            runCatching { context.unbindService(connection) }
        }
        logger("plugin guardian unbound plugin=${key.pluginId} reason=$reason")
    }

    private fun releaseAll(reason: String) {
        bindings.toMap().forEach { (key, state) -> releaseState(key, state, reason) }
    }

    private inner class GuardianConnection(
        private val key: PluginGrantKey,
        private val state: BindingState,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            dispatch {
                if (bindings[key] === state && state.connection === this) {
                    state.failureCount = 0
                    logger("plugin guardian connected plugin=${key.pluginId}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            dispatch {
                if (bindings[key] === state && state.connection === this) {
                    logger("plugin guardian disconnected plugin=${key.pluginId} awaiting_system_restart=true")
                }
            }
        }

        override fun onBindingDied(name: ComponentName) {
            dispatch { handleDeadBinding(key, state, this, "binding_died") }
        }

        override fun onNullBinding(name: ComponentName) {
            dispatch { handleDeadBinding(key, state, this, "null_binding") }
        }
    }

    companion object {
        private fun readStoppedFlag(packageManager: PackageManager, packageName: String): Boolean? {
            val applicationInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
            }.getOrNull() ?: return null
            return applicationInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
        }
    }
}
