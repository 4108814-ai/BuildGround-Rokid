package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import java.security.SecureRandom

/**
 * Credentials of the media-sync Wi-Fi Direct group.
 *
 * The `DIRECT-NS-` prefix is what we *ask* for. This ROM ignores it — it rejects config-based
 * group creation outright and hands back a framework-generated SSID instead — so the prefix only
 * survives on hardware where the configured path works. Either way the real credentials come from
 * the created group, and those are what the offer carries.
 */
data class MediaSyncP2pProfile(
    val networkName: String,
    val passphrase: String,
)

class MediaSyncP2pProfileStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The credentials we request on ROMs that accept a configured group. */
    fun loadOrCreateCandidate(): MediaSyncP2pProfile {
        val networkName = preferences.getString(KEY_NETWORK_NAME, null)
        val passphrase = preferences.getString(KEY_PASSPHRASE, null)
        if (!networkName.isNullOrBlank() && passphrase != null && passphrase.length >= 8) {
            return MediaSyncP2pProfile(networkName, passphrase)
        }
        val profile = MediaSyncP2pProfile(
            networkName = NETWORK_NAME_PREFIX + randomToken(6),
            passphrase = randomToken(24),
        )
        preferences.edit()
            .putString(KEY_NETWORK_NAME, profile.networkName)
            .putString(KEY_PASSPHRASE, profile.passphrase)
            .apply()
        return profile
    }

    /**
     * The credentials a group actually came up with. On this ROM they are framework-generated and
     * bear no relation to the candidate, which makes them the only truthful answer to "is this
     * group ours?" on a later session.
     */
    fun rememberActive(profile: MediaSyncP2pProfile) {
        preferences.edit()
            .putString(KEY_ACTIVE_NETWORK_NAME, profile.networkName)
            .putString(KEY_ACTIVE_PASSPHRASE, profile.passphrase)
            .apply()
    }

    fun activeNetworkName(): String? =
        preferences.getString(KEY_ACTIVE_NETWORK_NAME, null)?.takeIf(String::isNotBlank)

    private fun randomToken(length: Int): String {
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    companion object {
        const val NETWORK_NAME_PREFIX = "DIRECT-NS-"
        private const val PREFERENCES_NAME = "media_sync_p2p_profile"
        private const val KEY_NETWORK_NAME = "networkName"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_ACTIVE_NETWORK_NAME = "activeNetworkName"
        private const val KEY_ACTIVE_PASSPHRASE = "activePassphrase"
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}

enum class MediaSyncGroupOwnership {
    /** A media-sync group we created — recognised by the credentials recorded when it came up. */
    OURS,

    /** Recognisably the camera link's. Only identifiable where configured creation works. */
    CAMERA_LINK,

    /** Anything we cannot prove is ours. On this ROM the camera's group looks exactly like this. */
    FOREIGN,
}

enum class MediaSyncGroupAction {
    REUSE,
    REBUILD,

    /** Leave the group untouched and end the session; a later trigger tries again. */
    DEFER,
}

object MediaSyncGroupPolicy {
    const val CAMERA_NETWORK_NAME_PREFIX = "DIRECT-RN-"

    fun classify(
        networkName: String?,
        ourNetworkNames: Collection<String>,
    ): MediaSyncGroupOwnership = when {
        networkName.isNullOrBlank() -> MediaSyncGroupOwnership.FOREIGN
        ourNetworkNames.any { it.isNotBlank() && it == networkName } -> MediaSyncGroupOwnership.OURS
        networkName.startsWith(MediaSyncP2pProfileStore.NETWORK_NAME_PREFIX) ->
            MediaSyncGroupOwnership.OURS
        networkName.startsWith(CAMERA_NETWORK_NAME_PREFIX) -> MediaSyncGroupOwnership.CAMERA_LINK
        else -> MediaSyncGroupOwnership.FOREIGN
    }

    /**
     * Photo sync only ever removes a group it created.
     *
     * This ROM hands out framework-generated SSIDs, so the camera link's parked group — the one it
     * keeps alive for ~40 s to make warm reopens fast — is indistinguishable from any other
     * stranger's. Removing "foreign" groups would therefore mean removing the camera's, so an
     * unrecognised group defers the session instead. Nothing is lost: a parked group dies with the
     * Wi-Fi grace period, so deferral converges on its own.
     */
    fun action(ownership: MediaSyncGroupOwnership, usable: Boolean): MediaSyncGroupAction = when {
        ownership != MediaSyncGroupOwnership.OURS -> MediaSyncGroupAction.DEFER
        usable -> MediaSyncGroupAction.REUSE
        else -> MediaSyncGroupAction.REBUILD
    }
}

enum class MediaSyncP2pReadiness {
    /** The Wi-Fi Direct framework is enabled; a group can be created. */
    READY,

    /** The framework is disabled or unknown; wait for the enabled broadcast. */
    WAIT,
}

/**
 * Maps a Wi-Fi P2P state int to whether a group may be created.
 *
 * This is the crux of the first device sessions: on this ROM the P2P framework powers up ~288 ms
 * *after* station Wi-Fi and powers back down when idle, so `createGroup` issued off a bare
 * `isWifiEnabled` check lands in `P2pDisabledState` and returns `reason=0`. Only
 * `WIFI_P2P_STATE_ENABLED` means "safe to create".
 */
object MediaSyncP2pReadinessPolicy {
    fun readiness(p2pState: Int): MediaSyncP2pReadiness =
        if (p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            MediaSyncP2pReadiness.READY
        } else {
            MediaSyncP2pReadiness.WAIT
        }
}

/**
 * Creates and tears down the autonomous group owner that carries a media-sync session.
 *
 * Creation mirrors the camera link's proven ladder on this hardware: ask for our own SSID and
 * passphrase once, and when the ROM rejects that — which it does, with `reason=0` — fall back once
 * to the no-config overload and take whatever credentials the framework hands back.
 */
internal class MediaSyncGroup(
    context: Context,
    private val profileStore: MediaSyncP2pProfileStore,
    private val logger: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var candidate: MediaSyncP2pProfile? = null
    private var configuredAttempted = false
    private var legacyAttempted = false
    private var closed = false
    private var finished = false

    private var readyCallback: ((Ready) -> Unit)? = null
    private var failedCallback: ((String) -> Unit)? = null
    private var receiverRegistered = false
    private var deadlineMs = 0L
    private var waitPoll: Runnable? = null

    /** True while a create cycle is in flight, so the state signal does not start a second one. */
    private var cycleInFlight = false

    /**
     * Only ever true once a media-sync group is actually ours. [close] checks it before removing
     * anything: a session that deferred to somebody else's group must tear down nothing.
     */
    private var ownsGroup = false

    /**
     * The Wi-Fi Direct framework toggles independently of station Wi-Fi on this ROM, so the
     * enabled broadcast — not `isWifiEnabled` — is the signal that a group can be created.
     */
    private val p2pStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return
            onP2pState(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1))
        }
    }

    data class Ready(
        val profile: MediaSyncP2pProfile,
        val interfaceName: String,
    )

    fun create(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        readyCallback = onReady
        failedCallback = onFailed
        val wifiP2pManager = appContext
            .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            failOnce("no_p2p_service")
            return
        }
        manager = wifiP2pManager
        // initialize() also nudges the P2P framework awake; the enabled broadcast follows.
        channel = wifiP2pManager.initialize(appContext, Looper.getMainLooper(), null)
        if (channel == null) {
            failOnce("no_p2p_channel")
            return
        }
        candidate = profileStore.loadOrCreateCandidate()
        deadlineMs = SystemClock.elapsedRealtime() + P2P_WAIT_ATTEMPTS * P2P_WAIT_INTERVAL_MS
        registerReceiver()
        // The receiver reacts to future transitions; probe the current state to start now if the
        // framework is already up.
        queryP2pState()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        runCatching {
            appContext.registerReceiver(
                p2pStateReceiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION),
            )
            receiverRegistered = true
        }.onFailure { logger("mediaSync group p2p receiver failed error=${it.message}") }
    }

    private fun queryP2pState() {
        if (closed || finished || cycleInFlight) return
        val manager = manager ?: return
        val channel = channel ?: return
        runCatching {
            manager.requestP2pState(channel) { state -> onP2pState(state) }
        }.onFailure {
            // requestP2pState is unavailable; lean on the broadcast and the bounded wait instead.
            logger("mediaSync group p2p state query failed error=${it.message}")
            armWait()
        }
    }

    private fun onP2pState(p2pState: Int) {
        if (closed || finished) return
        if (MediaSyncP2pReadinessPolicy.readiness(p2pState) != MediaSyncP2pReadiness.READY) {
            armWait()
            return
        }
        if (cycleInFlight) return
        beginCreateCycle()
    }

    /**
     * Bounded wait for the framework to come up. The receiver normally drives creation the moment
     * it does; this timer is the backstop that re-probes if a broadcast is missed and fails the
     * session honestly once the budget — the camera link's own 16 x 750 ms — is spent.
     */
    private fun armWait() {
        if (closed || finished || cycleInFlight || waitPoll != null) return
        if (SystemClock.elapsedRealtime() >= deadlineMs) {
            failOnce("p2p_unavailable")
            return
        }
        waitPoll = Runnable {
            waitPoll = null
            queryP2pState()
        }.also { handler.postDelayed(it, P2P_WAIT_INTERVAL_MS) }
    }

    private fun clearWaitPoll() {
        waitPoll?.let(handler::removeCallbacks)
        waitPoll = null
    }

    private fun beginCreateCycle() {
        if (closed || finished || cycleInFlight) return
        cycleInFlight = true
        // Reset the ladder so a cycle that follows a dropped-framework retry can try again.
        configuredAttempted = false
        legacyAttempted = false
        clearWaitPoll()
        inspectThenCreate()
    }

    /**
     * A create that fails while we believed the framework was up means it dropped under us (it
     * powers down when idle). Only `reason=0` (ERROR / not-ready) is retriable; wait for the next
     * enabled signal, bounded by the deadline. Any other reason fails fast.
     */
    private fun handleCreateNotReady(reason: Int) {
        if (closed || finished) return
        cycleInFlight = false
        if (reason != WifiP2pManager.ERROR) {
            failOnce("group_create_failed_$reason")
            return
        }
        if (SystemClock.elapsedRealtime() >= deadlineMs) {
            failOnce("p2p_unavailable")
            return
        }
        logger("mediaSync group create not ready reason=$reason; awaiting p2p enabled")
        armWait()
    }

    private fun failOnce(reason: String) {
        if (finished) return
        finished = true
        clearWaitPoll()
        (failedCallback ?: return).invoke(reason)
    }

    private fun succeedOnce(ready: Ready) {
        if (finished) return
        finished = true
        clearWaitPoll()
        (readyCallback ?: return).invoke(ready)
    }

    private fun ourNetworkNames(): List<String> =
        listOfNotNull(profileStore.activeNetworkName(), candidate?.networkName)

    private fun inspectThenCreate() {
        val manager = manager ?: return failOnce("no_p2p_service")
        val channel = channel ?: return failOnce("no_p2p_channel")
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    createConfiguredGroup()
                    return@requestGroupInfo
                }
                val ownership = MediaSyncGroupPolicy.classify(group.networkName, ourNetworkNames())
                when (MediaSyncGroupPolicy.action(ownership, isUsable(group))) {
                    MediaSyncGroupAction.REUSE -> {
                        logger("mediaSync group reused ssid=${group.networkName}")
                        publish(group)
                    }
                    MediaSyncGroupAction.REBUILD -> {
                        logger("mediaSync group stale ssid=${group.networkName}")
                        removeGroup { createConfiguredGroup() }
                    }
                    MediaSyncGroupAction.DEFER -> {
                        logger(
                            "mediaSync group deferred ssid=${group.networkName} " +
                                "ownership=$ownership",
                        )
                        failOnce(MediaSyncStatusContract.REASON_CAMERA_GROUP_PARKED)
                    }
                }
            }
        }.onFailure {
            logger("mediaSync group info failed error=${it.message}")
            createConfiguredGroup()
        }
    }

    private fun isUsable(group: WifiP2pGroup): Boolean =
        group.isGroupOwner &&
            !group.networkName.isNullOrBlank() &&
            !group.passphrase.isNullOrBlank() &&
            !group.`interface`.isNullOrBlank()

    private fun createConfiguredGroup() {
        if (closed || finished || configuredAttempted) return
        val manager = manager ?: return failOnce("no_p2p_service")
        val channel = channel ?: return failOnce("no_p2p_channel")
        val expected = candidate ?: return failOnce("no_profile")
        configuredAttempted = true
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(expected.networkName)
                .setPassphrase(expected.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build()
        }.getOrElse {
            logger("mediaSync group configured build rejected type=${it.javaClass.simpleName}")
            scheduleLegacy()
            return
        }
        logger("mediaSync group create path=configured ssid=${expected.networkName}")
        runCatching {
            manager.createGroup(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.postDelayed({ resolveGroup() }, GROUP_SETTLE_MS)
                    }

                    override fun onFailure(reason: Int) {
                        // This ROM answers reason=0 here: it does not accept a caller-chosen SSID
                        // or passphrase. Rotating the profile would change nothing, so go straight
                        // to the overload the framework does honour.
                        logger("mediaSync group configured create rejected reason=$reason")
                        scheduleLegacy()
                    }
                },
            )
        }.onFailure {
            logger("mediaSync group configured create threw error=${it.message}")
            scheduleLegacy()
        }
    }

    private fun scheduleLegacy() {
        handler.postDelayed({ createLegacyGroup() }, LEGACY_FALLBACK_MS)
    }

    private fun createLegacyGroup() {
        if (closed || finished || legacyAttempted) return
        val manager = manager ?: return failOnce("no_p2p_service")
        val channel = channel ?: return failOnce("no_p2p_channel")
        legacyAttempted = true
        logger("mediaSync group create path=legacy")
        runCatching {
            manager.createGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.postDelayed({ resolveGroup() }, GROUP_SETTLE_MS)
                    }

                    override fun onFailure(reason: Int) {
                        // reason=0 here means the framework went back to sleep between the enabled
                        // signal and this call; wait for it to come back rather than giving up.
                        logger("mediaSync group legacy create failed reason=$reason")
                        handleCreateNotReady(reason)
                    }
                },
            )
        }.onFailure {
            logger("mediaSync group legacy create threw error=${it.message}")
            failOnce("group_create_threw")
        }
    }

    private fun resolveGroup() {
        if (closed || finished) return
        val manager = manager ?: return failOnce("no_p2p_service")
        val channel = channel ?: return failOnce("no_p2p_channel")
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) failOnce("group_missing") else publish(group)
            }
        }.onFailure { failOnce("group_info_threw") }
    }

    private fun publish(group: WifiP2pGroup) {
        if (closed || finished) return
        val networkName = group.networkName
        val passphrase = group.passphrase
        val interfaceName = group.`interface`
        if (networkName.isNullOrBlank() || passphrase.isNullOrBlank() || interfaceName.isNullOrBlank()) {
            failOnce("group_incomplete")
            return
        }
        val actual = MediaSyncP2pProfile(networkName, passphrase)
        // Record what the group really came up with: on this ROM it is framework-generated, and it
        // is the only thing that lets a later session recognise this group as ours.
        profileStore.rememberActive(actual)
        ownsGroup = true
        cycleInFlight = false
        logger("mediaSync group ready ssid=$networkName iface=$interfaceName")
        succeedOnce(Ready(actual, interfaceName))
    }

    private fun removeGroup(onComplete: () -> Unit) {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            onComplete()
            return
        }
        var settled = false
        val finish = {
            if (!settled) {
                settled = true
                handler.postDelayed(onComplete, REMOVE_SETTLE_MS)
            }
        }
        runCatching {
            manager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = finish()
                    override fun onFailure(reason: Int) = finish()
                },
            )
        }.onFailure { finish() }
        handler.postDelayed({ finish() }, REMOVE_CALLBACK_TIMEOUT_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        clearWaitPoll()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pStateReceiver) }
            receiverRegistered = false
        }
        val releaseChannel = {
            runCatching { channel?.close() }
            channel = null
            manager = null
        }
        if (ownsGroup) removeGroup(releaseChannel) else releaseChannel()
    }

    companion object {
        const val GROUP_SETTLE_MS = 350L
        const val REMOVE_SETTLE_MS = 350L
        const val REMOVE_CALLBACK_TIMEOUT_MS = 1_000L

        /** The same wait the camera link leaves before its own legacy fallback. */
        const val LEGACY_FALLBACK_MS = 150L

        /** Bounded wait for the P2P framework to enable — the camera link's own 16 x 750 ms. */
        const val P2P_WAIT_ATTEMPTS = 16
        const val P2P_WAIT_INTERVAL_MS = 750L
    }
}
