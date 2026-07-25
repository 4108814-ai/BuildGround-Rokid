package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

enum class MediaSyncState(val wireValue: String) {
    IDLE("idle"),
    PREPARING("preparing"),
    TRANSFERRING("transferring"),
    ;

    companion object {
        fun fromWireValue(value: String?): MediaSyncState? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Why an idle engine is not syncing right now — the single line the settings screen shows. */
enum class MediaSyncBlocker(val wireValue: String) {
    NOT_CHARGING("not_charging"),
    AUTO_SYNC_OFF("auto_sync_off"),
    LINK_DOWN("link_down"),
    CAMERA_ACTIVE("camera_active"),
    PHONE_WIFI_OFF("phone_wifi_off"),
    PHONE_PERMISSION("phone_permission"),

    /** The glasses' Wi-Fi Direct framework never came up in time (it powers down when idle). */
    GLASSES_WIFI_DIRECT("glasses_wifi_direct"),
    GLASSES_STORAGE_PERMISSION("glasses_storage_permission"),
    NOTHING_PENDING("nothing_pending"),
    ;

    companion object {
        fun fromWireValue(value: String?): MediaSyncBlocker? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class MediaSyncResult(val wireValue: String) {
    COMPLETED("completed"),
    UP_TO_DATE("up_to_date"),
    PARTIAL("partial"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWireValue(value: String?): MediaSyncResult? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class MediaSyncSettings(
    val autoSyncOnCharge: Boolean = true,
    val deleteAfterSync: Boolean = false,
)

data class MediaSyncProgress(
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val currentFile: String? = null,
)

data class MediaSyncRun(
    val finishedAtMillis: Long,
    val result: MediaSyncResult,
    val filesSynced: Int,
    val bytesSynced: Long,
    val filesFailed: Int,
    val filesDeleted: Int,
    val message: String? = null,
)

data class MediaSyncStatus(
    val state: MediaSyncState = MediaSyncState.IDLE,
    val blocker: MediaSyncBlocker? = null,
    val settings: MediaSyncSettings = MediaSyncSettings(),
    val progress: MediaSyncProgress = MediaSyncProgress(),
    val history: List<MediaSyncRun> = emptyList(),
    val syncedTotal: Int = 0,
    /** null until a delete-after-sync attempt has actually reported back from the glasses. */
    val deletionSupported: Boolean? = null,
)

/** JSON shapes for `/mediasync/status`, `/mediasync/settings` and `/mediasync/now`. */
object MediaSyncStatusContract {
    const val VERSION = 1
    const val MAX_HISTORY = 8
    const val MAX_MESSAGE_LENGTH = 160

    /**
     * `/mediasync/state` reason the glasses send when the camera link's Wi-Fi Direct group is
     * still parked. It is not a failure: the camera keeps that group alive for ~40 s so warm
     * reopens stay fast, and photo sync waits for the next trigger instead of stealing it.
     */
    const val REASON_CAMERA_GROUP_PARKED = "camera_group_parked"

    fun encode(status: MediaSyncStatus): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("state", status.state.wireValue)
        .put("syncedTotal", status.syncedTotal)
        .put("autoSyncOnCharge", status.settings.autoSyncOnCharge)
        .put("deleteAfterSync", status.settings.deleteAfterSync)
        .apply {
            status.blocker?.let { put("blocker", it.wireValue) }
            status.deletionSupported?.let { put("deletionSupported", it) }
            put(
                "progress",
                JSONObject()
                    .put("filesDone", status.progress.filesDone)
                    .put("filesTotal", status.progress.filesTotal)
                    .put("bytesDone", status.progress.bytesDone)
                    .put("bytesTotal", status.progress.bytesTotal)
                    .apply { status.progress.currentFile?.let { put("currentFile", it) } },
            )
            put(
                "history",
                JSONArray().apply {
                    status.history.take(MAX_HISTORY).forEach { run ->
                        put(
                            JSONObject()
                                .put("finishedAt", run.finishedAtMillis)
                                .put("result", run.result.wireValue)
                                .put("filesSynced", run.filesSynced)
                                .put("bytesSynced", run.bytesSynced)
                                .put("filesFailed", run.filesFailed)
                                .put("filesDeleted", run.filesDeleted)
                                .apply {
                                    run.message
                                        ?.take(MAX_MESSAGE_LENGTH)
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { put("message", it) }
                                },
                        )
                    }
                },
            )
        }

    fun decode(payload: JSONObject): MediaSyncStatus? {
        if (payload.optInt("version") != VERSION) return null
        val state = MediaSyncState.fromWireValue(payload.optString("state")) ?: return null
        val blocker = payload.optString("blocker").takeIf(String::isNotBlank)?.let {
            MediaSyncBlocker.fromWireValue(it) ?: return null
        }
        val progressJson = payload.optJSONObject("progress") ?: JSONObject()
        val historyJson = payload.optJSONArray("history") ?: JSONArray()
        val history = ArrayList<MediaSyncRun>(historyJson.length())
        for (index in 0 until minOf(historyJson.length(), MAX_HISTORY)) {
            val entry = historyJson.optJSONObject(index) ?: return null
            val result = MediaSyncResult.fromWireValue(entry.optString("result")) ?: return null
            history += MediaSyncRun(
                finishedAtMillis = entry.optLong("finishedAt"),
                result = result,
                filesSynced = entry.optInt("filesSynced"),
                bytesSynced = entry.optLong("bytesSynced"),
                filesFailed = entry.optInt("filesFailed"),
                filesDeleted = entry.optInt("filesDeleted"),
                message = entry.optString("message").takeIf(String::isNotBlank),
            )
        }
        return MediaSyncStatus(
            state = state,
            blocker = blocker,
            settings = MediaSyncSettings(
                autoSyncOnCharge = payload.optBoolean("autoSyncOnCharge", true),
                deleteAfterSync = payload.optBoolean("deleteAfterSync", false),
            ),
            progress = MediaSyncProgress(
                filesDone = progressJson.optInt("filesDone"),
                filesTotal = progressJson.optInt("filesTotal"),
                bytesDone = progressJson.optLong("bytesDone"),
                bytesTotal = progressJson.optLong("bytesTotal"),
                currentFile = progressJson.optString("currentFile").takeIf(String::isNotBlank),
            ),
            history = history,
            syncedTotal = payload.optInt("syncedTotal"),
            deletionSupported = if (payload.has("deletionSupported")) {
                payload.optBoolean("deletionSupported")
            } else {
                null
            },
        )
    }

    fun encodeSettingsRequest(
        autoSyncOnCharge: Boolean? = null,
        deleteAfterSync: Boolean? = null,
    ): JSONObject = JSONObject()
        .put("version", VERSION)
        .apply {
            autoSyncOnCharge?.let { put("autoSyncOnCharge", it) }
            deleteAfterSync?.let { put("deleteAfterSync", it) }
        }

    /** Applies a partial settings request; unknown or absent fields keep their current value. */
    fun applySettingsRequest(current: MediaSyncSettings, payload: JSONObject): MediaSyncSettings? {
        if (payload.optInt("version") != VERSION) return null
        val autoSync = if (payload.has("autoSyncOnCharge")) {
            payload.opt("autoSyncOnCharge") as? Boolean ?: return null
        } else {
            current.autoSyncOnCharge
        }
        val delete = if (payload.has("deleteAfterSync")) {
            payload.opt("deleteAfterSync") as? Boolean ?: return null
        } else {
            current.deleteAfterSync
        }
        return MediaSyncSettings(autoSync, delete)
    }
}
