package com.anezium.rokidbus.plugin.photosync

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncResult
import com.anezium.rokidbus.shared.MediaSyncRun
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import com.anezium.rokidbus.shared.MediaSyncStatusContract
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

internal interface PhotoSyncHost {
    fun send(path: String, payload: JSONObject): Boolean
    fun log(message: String)
}

/**
 * Everything the Photo Sync plugin does, minus Android.
 *
 * The plugin owns no sync state: the hub holds the ledger, the settings and the transfer, and
 * survives this process being killed. This runtime is a thin control surface — it mirrors the
 * last `/mediasync/status` push and turns UI intents into bus sends.
 */
internal class PhotoSyncRuntime(private val host: PhotoSyncHost) {
    private val listeners = CopyOnWriteArrayList<(MediaSyncStatus?) -> Unit>()

    @Volatile
    var status: MediaSyncStatus? = null
        private set

    fun observe(listener: (MediaSyncStatus?) -> Unit): () -> Unit {
        listeners += listener
        listener(status)
        return { listeners.remove(listener) }
    }

    fun onMessage(path: String, payload: JSONObject) {
        if (path != BusPaths.MEDIA_SYNC_STATUS) return
        val decoded = MediaSyncStatusContract.decode(payload)
        if (decoded == null) {
            host.log("photosync status rejected: malformed payload")
            return
        }
        status = decoded
        listeners.forEach { listener -> runCatching { listener(decoded) } }
    }

    /** An empty settings request is the refresh verb: the hub answers with a status push. */
    fun refresh(): Boolean =
        host.send(BusPaths.MEDIA_SYNC_SETTINGS, MediaSyncStatusContract.encodeSettingsRequest())

    fun setAutoSyncOnCharge(enabled: Boolean): Boolean = host.send(
        BusPaths.MEDIA_SYNC_SETTINGS,
        MediaSyncStatusContract.encodeSettingsRequest(autoSyncOnCharge = enabled),
    )

    fun setDeleteAfterSync(enabled: Boolean): Boolean = host.send(
        BusPaths.MEDIA_SYNC_SETTINGS,
        MediaSyncStatusContract.encodeSettingsRequest(deleteAfterSync = enabled),
    )

    fun syncNow(): Boolean =
        host.send(BusPaths.MEDIA_SYNC_NOW, JSONObject().put("version", MediaSyncStatusContract.VERSION))

    fun onDisconnected() {
        status = null
        listeners.forEach { listener -> runCatching { listener(null) } }
    }
}

/** Copy for the one status line the screen leads with — kept pure so it is unit-tested. */
internal object PhotoSyncCopy {
    fun headline(status: MediaSyncStatus?): String {
        if (status == null) return "Connecting to Rokid Nexus"
        return when (status.state) {
            MediaSyncState.PREPARING -> "Connecting to the glasses"
            MediaSyncState.TRANSFERRING -> {
                val total = status.progress.filesTotal
                if (total > 0) {
                    "Syncing ${(status.progress.filesDone + 1).coerceAtMost(total)} of $total"
                } else {
                    "Syncing"
                }
            }
            MediaSyncState.IDLE -> when (status.blocker) {
                MediaSyncBlocker.NOTHING_PENDING -> "Up to date"
                MediaSyncBlocker.NOT_CHARGING -> "Waiting for the glasses to charge"
                MediaSyncBlocker.AUTO_SYNC_OFF -> "Automatic sync is off"
                MediaSyncBlocker.LINK_DOWN -> "Glasses not connected"
                MediaSyncBlocker.CAMERA_ACTIVE -> "Paused while the camera is open"
                MediaSyncBlocker.PHONE_WIFI_OFF -> "Turn on Wi-Fi to sync"
                MediaSyncBlocker.GLASSES_STORAGE_PERMISSION -> "Allow storage access on the glasses"
                null -> "Ready"
            }
        }
    }

    fun detail(status: MediaSyncStatus?): String? {
        if (status == null) return null
        if (status.state == MediaSyncState.TRANSFERRING && status.progress.bytesTotal > 0) {
            return "${formatBytes(status.progress.bytesDone)} of " +
                formatBytes(status.progress.bytesTotal)
        }
        val last = status.history.firstOrNull() ?: return "No syncs yet"
        return "Last sync · ${describe(last)}"
    }

    fun describe(run: MediaSyncRun, locale: Locale = Locale.getDefault()): String {
        val outcome = when (run.result) {
            MediaSyncResult.COMPLETED -> "${run.filesSynced} ${plural(run.filesSynced)}"
            MediaSyncResult.UP_TO_DATE -> "Nothing new"
            MediaSyncResult.PARTIAL -> "${run.filesSynced} of ${run.filesSynced + run.filesFailed}"
            MediaSyncResult.FAILED -> run.message ?: "Failed"
        }
        if (run.result != MediaSyncResult.COMPLETED && run.result != MediaSyncResult.PARTIAL) {
            return outcome
        }
        return "$outcome · ${formatBytes(run.bytesSynced, locale)}"
    }

    /** Sizes follow the device locale, decimal comma included — this is gallery copy, not a log. */
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes >= 1_000_000_000L -> String.format(locale, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(locale, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun plural(count: Int): String = if (count == 1) "file" else "files"
}
