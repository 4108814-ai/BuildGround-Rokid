package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncLinkOffer
import com.anezium.rokidbus.shared.MediaSyncLinkOfferContract
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Photo sync, glasses side.
 *
 * Lives in the MAIN hub process — never in `:camera`. It reaches Wi-Fi and hub state through
 * [GlassesHub] in-process calls, exactly as the hub's own handlers do, and it learns about camera
 * sessions from the envelopes that already cross the process boundary. Touching `CameraLink` or
 * `CameraActivity` from here would mean touching a different process' statics, which has broken
 * the camera before.
 *
 * All work is serialised onto one daemon executor: the triggers arrive from a broadcast receiver,
 * the SPP reader thread and the CXR main thread, and none of them may block.
 */
internal object MediaSyncEngine {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RokidNexusMediaSync").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var catalog: MediaCatalog? = null
    @Volatile private var autoSyncOnCharge = true
    @Volatile private var consented = false
    @Volatile private var linkUp = false
    @Volatile private var cameraSessionActive = false

    private var session: Session? = null
    private var offerFuture: ScheduledFuture<*>? = null
    private var settlingFuture: ScheduledFuture<*>? = null
    private var watchdogFuture: ScheduledFuture<*>? = null

    private class Session(
        val id: String,
        val token: String,
        val group: MediaSyncGroup,
    ) {
        var server: MediaSyncFileServer? = null
        var offer: MediaSyncLinkOffer? = null
        var offersSent = 0
        var clientJoined = false
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> executor.execute {
                    attempt(MediaSyncTrigger.CHARGING_EDGE)
                }
                Intent.ACTION_POWER_DISCONNECTED -> logSync("charging edge=disconnected")
            }
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        this.appContext = appContext
        catalog = MediaCatalog()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(powerReceiver, filter)
            }
        }.onFailure { logError("mediaSync power receiver registration failed", it) }
        logSync("started")
    }

    /** The phone hub pushes its settings on connect and on every change. */
    fun onConfig(payload: JSONObject) {
        autoSyncOnCharge = payload.optBoolean("autoSyncOnCharge", true)
        consented = payload.optBoolean("consented", false)
        logSync("config autoSyncOnCharge=$autoSyncOnCharge consented=$consented")
        executor.execute { attempt(MediaSyncTrigger.BUS_CONNECT) }
    }

    /** `/mediasync/trigger` — the phone forwarded a "Sync now" press. */
    fun onTriggerRequest() {
        executor.execute { attempt(MediaSyncTrigger.MANUAL) }
    }

    fun onLinkStateChanged(up: Boolean) {
        val changed = linkUp != up
        linkUp = up
        if (!changed) return
        if (up) executor.execute { attempt(MediaSyncTrigger.BUS_CONNECT) } else executor.execute {
            finish("link_down")
        }
    }

    fun onCameraSessionChanged(active: Boolean) {
        cameraSessionActive = active
        if (active) executor.execute { finish(MediaSyncFileServer.ABORT_CAMERA) }
    }

    private fun attempt(trigger: MediaSyncTrigger) {
        val context = appContext ?: return
        val catalog = catalog ?: return
        if (!consented) {
            logSync("skip trigger=$trigger reason=not_consented")
            return
        }
        val storageReadable = hasStoragePermission(context)
        val scan = if (storageReadable) catalog.scan() else MediaCatalog.CatalogScan(emptyList(), false)
        val decision = MediaSyncTriggerPolicy.decide(
            trigger,
            MediaSyncConditions(
                linkUp = linkUp,
                charging = isCharging(context),
                hasEligibleFiles = !scan.isEmpty,
                cameraSessionActive = cameraSessionActive,
                autoSyncOnCharge = autoSyncOnCharge,
                syncInProgress = session != null,
                storageReadable = storageReadable,
            ),
        )
        when (decision) {
            is MediaSyncTriggerDecision.Skip -> {
                logSync("skip trigger=$trigger reason=${decision.reason}")
                reportState("idle", reason = decision.reason.name.lowercase())
                if (decision.reason == MediaSyncSkipReason.NOTHING_PENDING && scan.settling) {
                    scheduleSettlingRecheck(trigger)
                }
            }
            is MediaSyncTriggerDecision.Start -> begin(context, decision.trigger)
        }
    }

    /**
     * The stability gate needs two scans to clear a capture, so the first trigger after boot
     * always finds an empty catalog. Re-check once rather than making the wearer trigger twice.
     */
    private fun scheduleSettlingRecheck(trigger: MediaSyncTrigger) {
        if (settlingFuture?.isDone == false) return
        settlingFuture = executor.schedule(
            { attempt(trigger) },
            MediaSyncStabilityGate.MIN_SAMPLE_GAP_MS + SETTLING_MARGIN_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun begin(context: Context, trigger: MediaSyncTrigger) {
        val catalog = catalog ?: return
        val group = MediaSyncGroup(context, MediaSyncP2pProfileStore(context), ::logSync)
        val current = Session(UUID.randomUUID().toString(), randomToken(), group)
        session = current
        logSync("session begin trigger=$trigger id=${current.id}")
        reportState("preparing", reason = trigger.name.lowercase())
        GlassesHub.requestHubWifi(true)
        if (!awaitWifi(context)) {
            logSync("session abort reason=wifi_unavailable")
            finish("wifi_unavailable")
            return
        }
        if (cameraSessionActive) {
            // The wait above can span twelve seconds; a camera session may have opened inside it
            // and the camera always wins the radio.
            finish(MediaSyncFileServer.ABORT_CAMERA)
            return
        }
        val server = MediaSyncFileServer(
            catalog = catalog,
            token = current.token,
            deletionExecutor = AndroidMediaSyncDeletionExecutor(context, catalog, ::logSync),
            isCameraSessionActive = { cameraSessionActive },
            logger = ::logSync,
            onClientAuthenticated = { executor.execute { onClientJoined(current) } },
            onSessionFinished = { summary -> executor.execute { onServerFinished(current, summary) } },
        )
        current.server = server
        group.create(
            onReady = { ready -> executor.execute { onGroupReady(current, ready) } },
            onFailed = { reason -> executor.execute { onGroupFailed(current, reason) } },
        )
        watchdogFuture = executor.schedule(
            { if (session === current && !current.clientJoined) finish("join_timeout") },
            JOIN_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun onGroupReady(current: Session, ready: MediaSyncGroup.Ready) {
        if (session !== current) return
        val address = groupOwnerAddress(ready.interfaceName)
        val port = address?.let { current.server?.start(it) }
        if (address == null || port == null) {
            finish("server_bind_failed")
            return
        }
        current.offer = MediaSyncLinkOffer(
            sessionId = current.id,
            ssid = ready.profile.networkName,
            passphrase = ready.profile.passphrase,
            goIp = address.hostAddress.orEmpty(),
            port = port,
            token = current.token,
        )
        logSync("session offer ssid=${ready.profile.networkName} port=$port")
        sendOffer(current)
    }

    private fun onGroupFailed(current: Session, reason: String) {
        if (session !== current) return
        finish(reason)
    }

    private fun sendOffer(current: Session) {
        val offer = current.offer ?: return
        if (session !== current || current.clientJoined) return
        current.offersSent += 1
        GlassesHub.sendToPhone(
            BusPaths.MEDIA_SYNC_LINK_OFFER,
            MediaSyncLinkOfferContract.encode(offer),
        )
        if (current.offersSent >= MAX_OFFER_SENDS) return
        offerFuture = executor.schedule(
            { sendOffer(current) },
            OFFER_RETRY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun onClientJoined(current: Session) {
        if (session !== current) return
        current.clientJoined = true
        offerFuture?.cancel(false)
        offerFuture = null
        watchdogFuture?.cancel(false)
        watchdogFuture = executor.schedule(
            { if (session === current) finish("session_timeout") },
            SESSION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        logSync("session joined id=${current.id}")
        reportState("transferring", reason = null)
    }

    private fun onServerFinished(current: Session, summary: MediaSyncServerSummary) {
        if (session !== current) return
        log(
            "session served files=${summary.filesServed} bytes=${summary.bytesServed} " +
                "deleted=${summary.filesDeleted} deletionRefused=${summary.deletionRefused}",
        )
        reportState(
            state = "ended",
            reason = summary.abortReason,
            summary = summary,
        )
        finish(summary.abortReason ?: "completed", alreadyReported = true)
    }

    private fun finish(reason: String, alreadyReported: Boolean = false) {
        val current = session ?: return
        session = null
        offerFuture?.cancel(false)
        offerFuture = null
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        runCatching { current.server?.close() }
        runCatching { current.group.close() }
        GlassesHub.requestHubWifi(false)
        logSync("session end id=${current.id} reason=$reason")
        if (!alreadyReported) reportState("idle", reason)
    }

    private fun reportState(
        state: String,
        reason: String?,
        summary: MediaSyncServerSummary? = null,
    ) {
        val payload = JSONObject()
            .put("version", 1)
            .put("state", state)
            .apply {
                session?.let { put("sessionId", it.id) }
                reason?.let { put("reason", it) }
                summary?.let {
                    put("filesServed", it.filesServed)
                    put("bytesServed", it.bytesServed)
                    put("filesDeleted", it.filesDeleted)
                    put("deletionRefused", it.deletionRefused)
                }
            }
        GlassesHub.sendToPhone(BusPaths.MEDIA_SYNC_STATE, payload)
    }

    private fun hasStoragePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCharging(context: Context): Boolean {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * The ROM boots with Wi-Fi off and the hub enables it silently through the command bridge,
     * falling back to the accessibility toggle. The runway matches the camera link's: long enough
     * to outlast the accessibility panel sequence.
     */
    private fun awaitWifi(context: Context): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        repeat(MAX_WIFI_WAIT_ATTEMPTS) {
            if (runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)) return true
            runCatching { Thread.sleep(WIFI_WAIT_MS) }.onFailure { return false }
        }
        return runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun logSync(message: String) = log("mediaSync $message")

    private const val TOKEN_BYTES = 16
    private const val MAX_WIFI_WAIT_ATTEMPTS = 16
    private const val WIFI_WAIT_MS = 750L
    private const val OFFER_RETRY_MS = 2_500L
    private const val MAX_OFFER_SENDS = 10
    private const val JOIN_TIMEOUT_MS = 90_000L
    private const val SESSION_TIMEOUT_MS = 20 * 60_000L
    private const val SETTLING_MARGIN_MS = 500L
}
