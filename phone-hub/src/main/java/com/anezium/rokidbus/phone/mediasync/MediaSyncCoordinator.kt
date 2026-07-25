package com.anezium.rokidbus.phone.mediasync

import android.content.Context
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncLinkOfferContract
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncSettings
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import org.json.JSONObject

/** Translates the glasses engine's skip reasons into the one line the settings screen shows. */
object MediaSyncBlockerMapping {
    fun fromGlassesReason(reason: String?): MediaSyncBlocker? = when (reason) {
        "storage_permission" -> MediaSyncBlocker.GLASSES_STORAGE_PERMISSION
        "camera_active" -> MediaSyncBlocker.CAMERA_ACTIVE
        // The camera link parks its Wi-Fi Direct group for ~40 s after a session so warm reopens
        // stay fast. Photo sync waits it out rather than stealing the radio back.
        MediaSyncStatusContract.REASON_CAMERA_GROUP_PARKED -> MediaSyncBlocker.CAMERA_ACTIVE
        "link_down" -> MediaSyncBlocker.LINK_DOWN
        "nothing_pending" -> MediaSyncBlocker.NOTHING_PENDING
        "auto_sync_off" -> MediaSyncBlocker.AUTO_SYNC_OFF
        "not_charging" -> MediaSyncBlocker.NOT_CHARGING
        else -> null
    }

    fun fromLinkBlocker(blocker: MediaSyncLinkBlocker): MediaSyncBlocker = when (blocker) {
        MediaSyncLinkBlocker.PHONE_WIFI_OFF -> MediaSyncBlocker.PHONE_WIFI_OFF
        // Never claim Wi-Fi is off when it is on: a denied nearby-devices grant looks unfixable
        // if the screen sends the wearer to the wrong switch.
        MediaSyncLinkBlocker.MISSING_PERMISSION -> MediaSyncBlocker.PHONE_PERMISSION
        MediaSyncLinkBlocker.NO_P2P_SERVICE -> MediaSyncBlocker.PHONE_WIFI_OFF
    }
}

/**
 * Photo sync, phone side.
 *
 * Owns everything that must outlive the plugin process: the settings, the ledger, the gallery
 * writer and the transfer. The plugin is a control surface — it can be dead while a sync runs,
 * and a sync must run whether or not anyone is looking. What the plugin *does* provide is
 * consent: the engine stays dormant until an approved `mediasync` grant exists.
 */
internal class MediaSyncCoordinator(
    context: Context,
    private val sendToGlasses: (String, JSONObject) -> Boolean,
    private val publishStatus: (JSONObject) -> Unit,
    private val logger: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val store = MediaSyncSettingsStore(appContext)
    private val ledger = SyncLedger(SharedPreferencesSyncLedgerStorage(appContext))
    private val gallery: MediaSyncGalleryWriter = AndroidMediaSyncGalleryWriter(appContext, logger)
    private val link = MediaSyncLinkClient(appContext, ledger, gallery, logger, clock)

    private val lock = Any()
    private var settings: MediaSyncSettings = store.loadSettings()
    private var history: List<MediaSyncRun> = store.loadHistory()
    private var deletionSupported: Boolean? = store.loadDeletionSupported()
    private var state: MediaSyncState = MediaSyncState.IDLE
    private var blocker: MediaSyncBlocker? = null
    private var progress = MediaSyncProgress()
    private var consented = false
    private var transferring = false

    fun status(): MediaSyncStatus = synchronized(lock) {
        MediaSyncStatus(
            state = state,
            blocker = blocker,
            settings = settings,
            progress = progress,
            history = history,
            syncedTotal = ledger.size(),
            deletionSupported = deletionSupported,
        )
    }

    /** True while an approved plugin holds the `mediasync` capability. */
    fun onConsentChanged(consented: Boolean) {
        val changed = synchronized(lock) {
            val previous = this.consented
            this.consented = consented
            previous != consented
        }
        if (!changed) return
        logger("mediaSync consent=$consented")
        pushConfig()
        emitStatus()
    }

    fun onLinkUp() = pushConfig()

    fun applySettings(payload: JSONObject): Boolean {
        val next = synchronized(lock) {
            MediaSyncStatusContract.applySettingsRequest(settings, payload)?.also {
                settings = it
                store.saveSettings(it)
            }
        } ?: return false
        logger(
            "mediaSync settings autoSyncOnCharge=${next.autoSyncOnCharge} " +
                "deleteAfterSync=${next.deleteAfterSync}",
        )
        pushConfig()
        emitStatus()
        return true
    }

    fun requestSyncNow() {
        if (!consented) return
        logger("mediaSync manual trigger requested")
        val delivered = sendToGlasses(BusPaths.MEDIA_SYNC_TRIGGER, JSONObject().put("version", 1))
        if (delivered) return
        // A tap that reaches nothing must still show something: without this the button press
        // simply disappears whenever the glasses link is down.
        logger("mediaSync manual trigger undelivered: link down")
        synchronized(lock) {
            state = MediaSyncState.IDLE
            blocker = MediaSyncBlocker.LINK_DOWN
        }
        emitStatus()
    }

    /** `/mediasync/state` from the glasses engine: preparing, transferring, idle-with-reason. */
    fun onGlassesState(payload: JSONObject) {
        val reported = payload.optString("state")
        val reason = payload.optString("reason").takeIf(String::isNotBlank)
        synchronized(lock) {
            if (transferring) return@synchronized
            when (reported) {
                "preparing" -> {
                    state = MediaSyncState.PREPARING
                    blocker = null
                }
                "idle", "ended" -> {
                    state = MediaSyncState.IDLE
                    blocker = MediaSyncBlockerMapping.fromGlassesReason(reason)
                }
            }
        }
        emitStatus()
    }

    /** `/mediasync/link/offer` from the glasses: credentials for this session's data plane. */
    fun onLinkOffer(payload: JSONObject) {
        val offer = MediaSyncLinkOfferContract.decode(payload)
        if (offer == null) {
            logger("mediaSync offer rejected reason=malformed")
            return
        }
        val deleteAfterSync = synchronized(lock) {
            if (!consented || transferring) return
            transferring = true
            state = MediaSyncState.PREPARING
            blocker = null
            progress = MediaSyncProgress()
            settings.deleteAfterSync
        }
        emitStatus()
        val blocked = link.start(
            offer = offer,
            deleteAfterSync = deleteAfterSync,
            onProgress = ::onProgress,
            onDeletionOutcome = ::onDeletionOutcome,
            onFinished = ::onRunFinished,
        )
        if (blocked != null) {
            logger("mediaSync link blocked reason=$blocked")
            synchronized(lock) {
                transferring = false
                state = MediaSyncState.IDLE
                blocker = MediaSyncBlockerMapping.fromLinkBlocker(blocked)
            }
            emitStatus()
        }
    }

    private fun onProgress(update: MediaSyncProgress) {
        synchronized(lock) {
            progress = update
            state = MediaSyncState.TRANSFERRING
            blocker = null
        }
        emitStatus()
    }

    private fun onDeletionOutcome(supported: Boolean) {
        synchronized(lock) {
            if (deletionSupported == supported) return
            deletionSupported = supported
            store.saveDeletionSupported(supported)
        }
        emitStatus()
    }

    private fun onRunFinished(run: MediaSyncRun) {
        synchronized(lock) {
            transferring = false
            state = MediaSyncState.IDLE
            progress = MediaSyncProgress()
            blocker = if (run.result == MediaSyncResult.UP_TO_DATE) {
                MediaSyncBlocker.NOTHING_PENDING
            } else {
                null
            }
            history = (listOf(run) + history).take(MediaSyncStatusContract.MAX_HISTORY)
            store.saveHistory(history)
        }
        logger(
            "mediaSync run result=${run.result.wireValue} files=${run.filesSynced} " +
                "failed=${run.filesFailed} deleted=${run.filesDeleted}",
        )
        emitStatus()
    }

    private fun pushConfig() {
        val payload = synchronized(lock) {
            JSONObject()
                .put("version", 1)
                .put("autoSyncOnCharge", settings.autoSyncOnCharge)
                .put("consented", consented)
        }
        sendToGlasses(BusPaths.MEDIA_SYNC_CONFIG, payload)
    }

    private fun emitStatus() {
        runCatching { publishStatus(MediaSyncStatusContract.encode(status())) }
            .onFailure { logger("mediaSync status publish failed error=${it.message}") }
    }

    override fun close() = link.close()
}

/** SharedPreferences-backed settings, run history and the observed deletion capability. */
internal class MediaSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): MediaSyncSettings = MediaSyncSettings(
        autoSyncOnCharge = preferences.getBoolean(KEY_AUTO_SYNC, true),
        deleteAfterSync = preferences.getBoolean(KEY_DELETE_AFTER_SYNC, false),
    )

    fun saveSettings(settings: MediaSyncSettings) {
        preferences.edit()
            .putBoolean(KEY_AUTO_SYNC, settings.autoSyncOnCharge)
            .putBoolean(KEY_DELETE_AFTER_SYNC, settings.deleteAfterSync)
            .apply()
    }

    fun loadDeletionSupported(): Boolean? =
        if (preferences.contains(KEY_DELETION_SUPPORTED)) {
            preferences.getBoolean(KEY_DELETION_SUPPORTED, false)
        } else {
            null
        }

    fun saveDeletionSupported(supported: Boolean) {
        preferences.edit().putBoolean(KEY_DELETION_SUPPORTED, supported).apply()
    }

    fun loadHistory(): List<MediaSyncRun> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { MediaSyncStatusContract.decode(JSONObject(raw))?.history }
            .getOrNull()
            .orEmpty()
    }

    fun saveHistory(history: List<MediaSyncRun>) {
        val encoded = MediaSyncStatusContract.encode(MediaSyncStatus(history = history)).toString()
        preferences.edit().putString(KEY_HISTORY, encoded).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_media_sync"
        const val KEY_AUTO_SYNC = "auto_sync_on_charge"
        const val KEY_DELETE_AFTER_SYNC = "delete_after_sync"
        const val KEY_DELETION_SUPPORTED = "deletion_supported"
        const val KEY_HISTORY = "history_v1"
    }
}
