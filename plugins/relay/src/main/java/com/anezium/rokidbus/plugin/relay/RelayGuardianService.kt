package com.anezium.rokidbus.plugin.relay

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

class RelayGuardianService : Service() {
    private val binder = Binder()
    private val main = Handler(Looper.getMainLooper())
    private val repairPolicy = RelayListenerRepairPolicy()
    private val listenerComponent by lazy { ComponentName(this, RelayNotificationListener::class.java) }
    private var destroyed = false

    private val retryEvaluation = Runnable { evaluateListenerHealth() }
    private val periodicEvaluation = object : Runnable {
        override fun run() {
            if (destroyed) return
            evaluateListenerHealth()
            main.postDelayed(this, HEALTH_EVALUATION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        RelayDiagnostics.recordGuardianCreated(this, NotificationControl.isListenerConnected())
        snapshotProcessExitReasons()
        snapshotProcessStartReasons()
        evaluateListenerHealth()
        main.postDelayed(periodicEvaluation, HEALTH_EVALUATION_INTERVAL_MS)
        Log.i(TAG, "guardian created")
    }

    override fun onBind(intent: Intent?): IBinder {
        RelayDiagnostics.recordGuardianBound(this, bound = true)
        Log.i(TAG, "guardian bound")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        RelayDiagnostics.recordGuardianBound(this, bound = false)
        Log.i(TAG, "guardian unbound")
        return false
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(retryEvaluation)
        main.removeCallbacks(periodicEvaluation)
        if (activeService === this) activeService = null
        RelayDiagnostics.recordGuardianDestroyed(this)
        Log.i(TAG, "guardian destroyed")
        super.onDestroy()
    }

    private fun evaluateListenerHealth() {
        if (destroyed) return
        val snapshot = RelayDiagnostics.snapshot(this)
        val decision = repairPolicy.evaluate(
            RelayDiagnostics.repairState(this),
            RelayRepairInput(
                nowUptimeMs = SystemClock.uptimeMillis(),
                accessGranted = snapshot.notificationAccessGranted,
                listenerConnected = NotificationControl.isListenerConnected(),
                connectGeneration = snapshot.listenerConnectGeneration,
                apiLevel = Build.VERSION.SDK_INT,
                hasLiveListenerInstance = NotificationControl.hasLiveListenerInstance(),
            ),
        )
        RelayDiagnostics.recordAccessState(this, snapshot.notificationAccessGranted)
        RelayDiagnostics.recordRepairDecision(this, decision, snapshot.listenerConnectGeneration)
        executeRepairAction(decision.action, decision.state, snapshot.listenerConnectGeneration)
        scheduleRetry(decision.nextEvaluationDelayMs)
    }

    private fun executeRepairAction(
        action: RelayRepairAction,
        state: RelayRepairState,
        connectGeneration: Long,
    ) {
        try {
            when (action) {
                RelayRepairAction.NONE -> Unit
                RelayRepairAction.REQUEST_REBIND,
                RelayRepairAction.REQUEST_REBIND_AGAIN,
                -> NotificationListenerService.requestRebind(listenerComponent)

                RelayRepairAction.REQUEST_STATIC_UNBIND_REBIND -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        NotificationListenerService.requestUnbind(listenerComponent)
                        NotificationListenerService.requestRebind(listenerComponent)
                    } else {
                        recordUnsupportedRepairAction(state, connectGeneration)
                    }
                }

                RelayRepairAction.REQUEST_INSTANCE_UNBIND_REBIND -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        NotificationControl.requestListenerUnbind()
                        NotificationListenerService.requestRebind(listenerComponent)
                    } else {
                        recordUnsupportedRepairAction(state, connectGeneration)
                    }
                }
            }
        } catch (_: RuntimeException) {
            RelayDiagnostics.recordRepairCommandFailed(this, state, connectGeneration)
            Log.w(TAG, "listener repair command failed")
        }
    }

    private fun recordUnsupportedRepairAction(state: RelayRepairState, connectGeneration: Long) {
        RelayDiagnostics.recordRepairCommandFailed(this, state, connectGeneration)
        Log.w(TAG, "listener repair action did not match platform API")
    }

    private fun scheduleRetry(delayMs: Long?) {
        main.removeCallbacks(retryEvaluation)
        if (delayMs != null && !destroyed) {
            main.postDelayed(retryEvaluation, delayMs.coerceAtLeast(1L))
        }
    }

    private fun snapshotProcessExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = getSystemService(ActivityManager::class.java) ?: return
        try {
            activityManager.getHistoricalProcessExitReasons(packageName, 0, PROCESS_HISTORY_LIMIT)
                .take(PROCESS_HISTORY_LIMIT)
                .forEachIndexed { index, info ->
                    RelayDiagnostics.recordProcessExit(
                        this,
                        timestampMs = info.timestamp,
                        reason = info.reason,
                        status = info.status,
                        latest = index == 0,
                    )
                }
        } catch (_: RuntimeException) {
            RelayDiagnostics.recordProcessSnapshotFailure(this, RelayDiagnosticState.PROCESS_EXIT)
            Log.w(TAG, "process exit snapshot failed")
        }
    }

    private fun snapshotProcessStartReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val activityManager = getSystemService(ActivityManager::class.java) ?: return
        RelayDiagnostics.clearCurrentProcessStart(this)
        try {
            activityManager.getHistoricalProcessStartReasons(PROCESS_HISTORY_LIMIT)
                .take(PROCESS_HISTORY_LIMIT)
                .forEachIndexed { index, info ->
                    RelayDiagnostics.recordProcessStart(
                        this,
                        reason = info.reason,
                        startType = info.startType,
                        forceStopped = info.wasForceStopped(),
                        latest = index == 0,
                    )
                }
        } catch (_: RuntimeException) {
            RelayDiagnostics.recordProcessSnapshotFailure(this, RelayDiagnosticState.PROCESS_START)
            Log.w(TAG, "process start snapshot failed")
        }
    }

    companion object {
        @Volatile
        private var activeService: RelayGuardianService? = null

        /** Same-process wake-up from listener and companion callbacks; no IPC is involved. */
        fun requestImmediateHealthEvaluation(): Boolean {
            val service = activeService ?: return false
            service.main.post(service.retryEvaluation)
            return true
        }

        private const val TAG = "NexusRelayGuardian"
        private const val HEALTH_EVALUATION_INTERVAL_MS = 5L * 60L * 1_000L
        private const val PROCESS_HISTORY_LIMIT = 5
    }
}
