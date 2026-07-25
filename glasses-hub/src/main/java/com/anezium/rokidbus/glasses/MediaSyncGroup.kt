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
 * The SSID prefix is `DIRECT-NS-`, deliberately different from the camera link's `DIRECT-RN-`:
 * the Lens plugin's persistent-group janitor deletes every profile matching its own prefix, and
 * photo sync must be neither its victim nor its cause.
 */
data class MediaSyncP2pProfile(
    val networkName: String,
    val passphrase: String,
)

enum class MediaSyncGroupOwnership {
    /** A media-sync group we created, safe to reuse or rebuild. */
    OURS,

    /**
     * The camera link's group. It is parked on purpose for ~40 s after a camera session so a
     * warm reopen costs 1.4 s instead of 5-7 s; removing it would silently degrade the camera.
     */
    CAMERA_LINK,

    /** Somebody else's group — the only kind photo sync may tear down. */
    FOREIGN,
}

object MediaSyncGroupPolicy {
    const val CAMERA_NETWORK_NAME_PREFIX = "DIRECT-RN-"

    fun classify(networkName: String?, expectedNetworkName: String): MediaSyncGroupOwnership = when {
        !networkName.isNullOrBlank() && networkName == expectedNetworkName ->
            MediaSyncGroupOwnership.OURS
        networkName != null && networkName.startsWith(MediaSyncP2pProfileStore.NETWORK_NAME_PREFIX) ->
            MediaSyncGroupOwnership.OURS
        networkName != null && networkName.startsWith(CAMERA_NETWORK_NAME_PREFIX) ->
            MediaSyncGroupOwnership.CAMERA_LINK
        else -> MediaSyncGroupOwnership.FOREIGN
    }
}

class MediaSyncP2pProfileStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadOrCreate(): MediaSyncP2pProfile {
        val networkName = preferences.getString(KEY_NETWORK_NAME, null)
        val passphrase = preferences.getString(KEY_PASSPHRASE, null)
        if (!networkName.isNullOrBlank() && passphrase != null && passphrase.length >= 8) {
            return MediaSyncP2pProfile(networkName, passphrase)
        }
        return rotate()
    }

    fun rotate(): MediaSyncP2pProfile {
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
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}

/**
 * Creates and tears down the autonomous group owner that carries a media-sync session.
 *
 * Only one Wi-Fi Direct group can exist on the device, which is exactly why photo sync refuses to
 * run while a camera session is live: the two would fight over the radio and the camera always
 * wins. Any pre-existing group we do not own is removed before ours is created, mirroring the
 * camera link's conflict recovery.
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
    private var profile: MediaSyncP2pProfile? = null
    private var createAttempts = 0
    private var closed = false

    /**
     * Only ever true once a media-sync group is actually ours. [close] checks it before removing
     * anything: a session that deferred to the camera's parked group must tear down nothing, or
     * it would destroy the very group it just refused to touch.
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
        profile = profileStore.loadOrCreate()
        inspectThenCreate(onReady, onFailed)
    }

    private fun inspectThenCreate(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        val expected = profile ?: return onFailed("no_profile")
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    createGroup(onReady, onFailed)
                    return@requestGroupInfo
                }
                when (MediaSyncGroupPolicy.classify(group.networkName, expected.networkName)) {
                    MediaSyncGroupOwnership.OURS ->
                        if (isUsable(group, expected)) {
                            logger("mediaSync group reused ssid=${group.networkName}")
                            publish(group, onReady, onFailed)
                        } else {
                            logger("mediaSync group stale ssid=${group.networkName}")
                            removeGroup { createGroup(onReady, onFailed) }
                        }
                    MediaSyncGroupOwnership.CAMERA_LINK -> {
                        // The camera parks this group on purpose; taking it would cost the wearer
                        // a cold camera reopen. Wait for the next trigger instead.
                        logger("mediaSync group deferred ssid=${group.networkName} reason=camera")
                        onFailed(MediaSyncStatusContract.REASON_CAMERA_GROUP_PARKED)
                    }
                    MediaSyncGroupOwnership.FOREIGN -> {
                        logger("mediaSync group conflict ssid=${group.networkName}")
                        removeGroup { createGroup(onReady, onFailed) }
                    }
                }
            }
        }.onFailure {
            logger("mediaSync group info failed error=${it.message}")
            createGroup(onReady, onFailed)
        }
    }

    private fun isUsable(group: WifiP2pGroup, expected: MediaSyncP2pProfile): Boolean =
        group.isGroupOwner &&
            group.networkName == expected.networkName &&
            !group.passphrase.isNullOrBlank() &&
            !group.`interface`.isNullOrBlank()

    private fun createGroup(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
        val manager = manager ?: return onFailed("no_p2p_service")
        val channel = channel ?: return onFailed("no_p2p_channel")
        val expected = profile ?: return onFailed("no_profile")
        createAttempts += 1
        val config = WifiP2pConfig.Builder()
            .setNetworkName(expected.networkName)
            .setPassphrase(expected.passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .enablePersistentMode(true)
            .build()
        runCatching {
            manager.createGroup(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.postDelayed({ resolveGroup(onReady, onFailed) }, GROUP_SETTLE_MS)
                    }

                    override fun onFailure(reason: Int) {
                        logger("mediaSync group create failed reason=$reason attempt=$createAttempts")
                        if (createAttempts < MAX_CREATE_ATTEMPTS) {
                            // A stale persistent profile is the usual cause; rotate and retry once.
                            profile = profileStore.rotate()
                            removeGroup { createGroup(onReady, onFailed) }
                        } else {
                            onFailed("group_create_failed_$reason")
                        }
                    }
                },
            )
        }.onFailure {
            logger("mediaSync group create threw error=${it.message}")
            onFailed("group_create_threw")
        }
    }

    private fun resolveGroup(onReady: (Ready) -> Unit, onFailed: (String) -> Unit) {
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
        ownsGroup = true
        onReady(Ready(MediaSyncP2pProfile(networkName, passphrase), interfaceName))
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
        const val MAX_CREATE_ATTEMPTS = 2
    }
}
