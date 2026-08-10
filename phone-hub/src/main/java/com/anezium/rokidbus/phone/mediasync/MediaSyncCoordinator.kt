package com.anezium.rokidbus.phone.mediasync

import android.content.Context
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncProgress
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncSettings
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import org.json.JSONObject
import java.util.concurrent.Executors

/** Translates the glasses engine's skip reasons into the one line the settings screen shows. */
object MediaSyncBlockerMapping {
    fun fromGlassesReason(reason: String?): MediaSyncBlocker? = when (reason) {
        "storage_permission" -> MediaSyncBlocker.GLASSES_STORAGE_PERMISSION
        "camera_active" -> MediaSyncBlocker.CAMERA_ACTIVE
        "link_down" -> MediaSyncBlocker.LINK_DOWN
        "nothing_pending" -> MediaSyncBlocker.NOTHING_PENDING
        "auto_sync_off" -> MediaSyncBlocker.AUTO_SYNC_OFF
        "not_charging" -> MediaSyncBlocker.NOT_CHARGING
        else -> null
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
    private val staging = MediaSyncStagingStore(MediaSyncStagingStore.directoryFor(appContext))

    /**
     * The data plane is an ordered byte stream, so it gets one thread and keeps it. The SPP reader
     * is a single producer submitting in wire order, and a single-threaded FIFO consumer is what
     * makes "chunk N is appended before chunk N+1" a property of the code rather than a hope.
     */
    private val transferExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-sync-receive").apply { isDaemon = true }
    }

    private val lock = Any()
    private var settings: MediaSyncSettings = store.loadSettings()
    private var history: List<MediaSyncRun> = store.loadHistory()
    private var deletionSupported: Boolean? = store.loadDeletionSupported()
    private var state: MediaSyncState = MediaSyncState.IDLE
    private var blocker: MediaSyncBlocker? = null
    private var progress = MediaSyncProgress()
    private var consented = false
    private var transferring = false
    private var receiver: MediaSyncTransferReceiver? = null

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

    /**
     * Applies a settings request, or answers a bare one with the current status.
     *
     * The settings screen refreshes through this path, so an unchanged request must stay free:
     * it neither rewrites preferences nor pushes config across the link photo sync is otherwise
     * careful not to crowd. Only a real glasses-side setting change travels to the glasses.
     */
    fun applySettings(payload: JSONObject): Boolean {
        var glassesConfigChanged = false
        val changed = synchronized(lock) {
            val next = MediaSyncStatusContract.applySettingsRequest(settings, payload)
                ?: return false
            if (next == settings) {
                false
            } else {
                glassesConfigChanged = next.mode != settings.mode
                settings = next
                store.saveSettings(next)
                true
            }
        }
        if (changed) {
            val current = synchronized(lock) { settings }
            logger(
                "mediaSync settings mode=${current.mode.wireValue} " +
                    "deleteAfterSync=${current.deleteAfterSync}",
            )
            if (glassesConfigChanged) pushConfig()
        }
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
        var startingSessionId: String? = null
        synchronized(lock) {
            if (transferring) return@synchronized
            when (reported) {
                "preparing" -> {
                    state = MediaSyncState.PREPARING
                    blocker = null
                    startingSessionId = payload.optString("sessionId").takeIf(String::isNotBlank)
                }
                "idle", "ended" -> {
                    state = MediaSyncState.IDLE
                    blocker = MediaSyncBlockerMapping.fromGlassesReason(reason)
                }
            }
        }
        emitStatus()
        startingSessionId?.let(::beginSession)
    }

    /**
     * The glasses opened a session and are waiting to be driven. Everything from here is ordinary
     * bus traffic: no transport to negotiate, nothing that can fail before the first byte moves.
     */
    private fun beginSession(sessionId: String) {
        val sessionSettings = synchronized(lock) {
            if (!consented || transferring) return
            transferring = true
            state = MediaSyncState.PREPARING
            blocker = null
            progress = MediaSyncProgress()
            settings
        }
        emitStatus()
        val session = MediaSyncTransferReceiver(
            sessionId = sessionId,
            ledger = ledger,
            gallery = gallery,
            staging = staging,
            settings = sessionSettings,
            clock = clock,
            logger = logger,
            send = sendToGlasses,
            onProgress = ::onProgress,
            onDeletionOutcome = ::onDeletionOutcome,
            onFinished = ::onRunFinished,
        )
        synchronized(lock) { receiver = session }
        logger("mediaSync session begin id=$sessionId deleteAfterSync=${sessionSettings.deleteAfterSync}")
        // Start on the receive thread too, so nothing in the session ever runs anywhere else.
        runCatching { transferExecutor.execute { session.start() } }
            .onFailure { logger("mediaSync session start rejected") }
    }

    /** Data-plane traffic from the glasses, serialised onto the receive thread in wire order. */
    fun onTransferEnvelope(path: String, payload: JSONObject, binary: ByteArray?) {
        runCatching {
            transferExecutor.execute {
                val session = synchronized(lock) { receiver } ?: return@execute
                session.onEnvelope(path, payload, binary)
            }
        }.onFailure { logger("mediaSync transfer dispatch rejected path=$path") }
    }

    /** Keeps the staged partials and reports honestly; the next session resumes from them. */
    fun onLinkLost() {
        runCatching {
            transferExecutor.execute {
                val session = synchronized(lock) { receiver } ?: return@execute
                session.onLinkLost()
            }
        }.onFailure { logger("mediaSync link-lost dispatch rejected") }
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
            receiver = null
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
                .put("syncMode", settings.mode.wireValue)
                .put("consented", consented)
        }
        sendToGlasses(BusPaths.MEDIA_SYNC_CONFIG, payload)
    }

    private fun emitStatus() {
        runCatching { publishStatus(MediaSyncStatusContract.encode(status())) }
            .onFailure { logger("mediaSync status publish failed error=${it.message}") }
    }

    override fun close() {
        synchronized(lock) { receiver = null }
        transferExecutor.shutdownNow()
    }
}

/** SharedPreferences-backed settings, run history and the observed deletion capability. */
internal class MediaSyncSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): MediaSyncSettings = MediaSyncSettings(
        mode = MediaSyncMode.fromWireValue(preferences.getString(KEY_SYNC_MODE, null))
            ?: MediaSyncMode.CHARGING,
        deleteAfterSync = preferences.getBoolean(KEY_DELETE_AFTER_SYNC, false),
        syncNormalPhotos = preferences.getBoolean(KEY_SYNC_NORMAL_PHOTOS, true),
        syncArPhotos = preferences.getBoolean(KEY_SYNC_AR_PHOTOS, false),
        syncNormalVideos = preferences.getBoolean(KEY_SYNC_NORMAL_VIDEOS, false),
        syncArVideos = preferences.getBoolean(KEY_SYNC_AR_VIDEOS, false),
    )

    fun saveSettings(settings: MediaSyncSettings) {
        preferences.edit()
            .putString(KEY_SYNC_MODE, settings.mode.wireValue)
            .putBoolean(KEY_DELETE_AFTER_SYNC, settings.deleteAfterSync)
            .putBoolean(KEY_SYNC_NORMAL_PHOTOS, settings.syncNormalPhotos)
            .putBoolean(KEY_SYNC_AR_PHOTOS, settings.syncArPhotos)
            .putBoolean(KEY_SYNC_NORMAL_VIDEOS, settings.syncNormalVideos)
            .putBoolean(KEY_SYNC_AR_VIDEOS, settings.syncArVideos)
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
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_DELETE_AFTER_SYNC = "delete_after_sync"
        const val KEY_SYNC_NORMAL_PHOTOS = "sync_normal_photos"
        const val KEY_SYNC_AR_PHOTOS = "sync_ar_photos"
        const val KEY_SYNC_NORMAL_VIDEOS = "sync_normal_videos"
        const val KEY_SYNC_AR_VIDEOS = "sync_ar_videos"
        const val KEY_DELETION_SUPPORTED = "deletion_supported"
        const val KEY_HISTORY = "history_v1"
    }
}
