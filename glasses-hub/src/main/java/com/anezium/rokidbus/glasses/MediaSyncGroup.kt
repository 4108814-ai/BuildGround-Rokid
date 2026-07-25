package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
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

    /**
     * Only ever true once a media-sync group is actually ours. [close] checks it before removing
     * anything: a session that deferred to somebody else's group must tear down nothing.
     */
    private var ownsGroup = false

    data class Ready(
        val profile: MediaSyncP2pProfile,
        val interfaceName: String,
    )

    fun create(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        val wifiP2pManager = appContext
            .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            onFailed("no_p2p_service")
            return
        }
        manager = wifiP2pManager
        channel = wifiP2pManager.initialize(appContext, Looper.getMainLooper(), null)
        if (channel == null) {
            onFailed("no_p2p_channel")
            return
        }
        candidate = profileStore.loadOrCreateCandidate()
        inspectThenCreate(onReady, onFailed)
    }

    private fun ourNetworkNames(): List<String> =
        listOfNotNull(profileStore.activeNetworkName(), candidate?.networkName)

    private fun inspectThenCreate(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    createConfiguredGroup(onReady, onFailed)
                    return@requestGroupInfo
                }
                val ownership = MediaSyncGroupPolicy.classify(group.networkName, ourNetworkNames())
                when (MediaSyncGroupPolicy.action(ownership, isUsable(group))) {
                    MediaSyncGroupAction.REUSE -> {
                        logger("mediaSync group reused ssid=${group.networkName}")
                        publish(group, onReady, onFailed)
                    }
                    MediaSyncGroupAction.REBUILD -> {
                        logger("mediaSync group stale ssid=${group.networkName}")
                        removeGroup { createConfiguredGroup(onReady, onFailed) }
                    }
                    MediaSyncGroupAction.DEFER -> {
                        logger(
                            "mediaSync group deferred ssid=${group.networkName} " +
                                "ownership=$ownership",
                        )
                        onFailed(MediaSyncStatusContract.REASON_CAMERA_GROUP_PARKED)
                    }
                }
            }
        }.onFailure {
            logger("mediaSync group info failed error=${it.message}")
            createConfiguredGroup(onReady, onFailed)
        }
    }

    private fun isUsable(group: WifiP2pGroup): Boolean =
        group.isGroupOwner &&
            !group.networkName.isNullOrBlank() &&
            !group.passphrase.isNullOrBlank() &&
            !group.`interface`.isNullOrBlank()

    private fun createConfiguredGroup(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        if (closed || configuredAttempted) return
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        val expected = candidate ?: return onFailed("no_profile")
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
            scheduleLegacy(onReady, onFailed)
            return
        }
        logger("mediaSync group create path=configured ssid=${expected.networkName}")
        runCatching {
            manager.createGroup(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.postDelayed({ resolveGroup(onReady, onFailed) }, GROUP_SETTLE_MS)
                    }

                    override fun onFailure(reason: Int) {
                        // This ROM answers reason=0 here: it does not accept a caller-chosen SSID
                        // or passphrase. Rotating the profile would change nothing, so go straight
                        // to the overload the framework does honour.
                        logger("mediaSync group configured create rejected reason=$reason")
                        scheduleLegacy(onReady, onFailed)
                    }
                },
            )
        }.onFailure {
            logger("mediaSync group configured create threw error=${it.message}")
            scheduleLegacy(onReady, onFailed)
        }
    }

    private fun scheduleLegacy(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        handler.postDelayed({ createLegacyGroup(onReady, onFailed) }, LEGACY_FALLBACK_MS)
    }

    private fun createLegacyGroup(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        if (closed || legacyAttempted) return
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        legacyAttempted = true
        logger("mediaSync group create path=legacy")
        runCatching {
            manager.createGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.postDelayed({ resolveGroup(onReady, onFailed) }, GROUP_SETTLE_MS)
                    }

                    override fun onFailure(reason: Int) {
                        logger("mediaSync group legacy create failed reason=$reason")
                        onFailed("group_create_failed_$reason")
                    }
                },
            )
        }.onFailure {
            logger("mediaSync group legacy create threw error=${it.message}")
            onFailed("group_create_threw")
        }
    }

    private fun resolveGroup(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        if (closed) return
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) onFailed("group_missing") else publish(group, onReady, onFailed)
            }
        }.onFailure { onFailed("group_info_threw") }
    }

    private fun publish(
        group: WifiP2pGroup,
        onReady: (Ready) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (closed) return
        val networkName = group.networkName
        val passphrase = group.passphrase
        val interfaceName = group.`interface`
        if (networkName.isNullOrBlank() || passphrase.isNullOrBlank() || interfaceName.isNullOrBlank()) {
            onFailed("group_incomplete")
            return
        }
        val actual = MediaSyncP2pProfile(networkName, passphrase)
        // Record what the group really came up with: on this ROM it is framework-generated, and it
        // is the only thing that lets a later session recognise this group as ours.
        profileStore.rememberActive(actual)
        ownsGroup = true
        logger("mediaSync group ready ssid=$networkName iface=$interfaceName")
        onReady(Ready(actual, interfaceName))
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
    }
}
