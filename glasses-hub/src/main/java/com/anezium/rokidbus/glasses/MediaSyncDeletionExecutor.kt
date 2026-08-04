package com.anezium.rokidbus.glasses

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File

enum class MediaSyncDeletionOutcome(val wireValue: String) {
    DELETED("deleted"),
    ALREADY_GONE("already_gone"),

    /** The ROM refused: scoped storage owns the file and the hub cannot get consent headlessly. */
    NOT_PERMITTED("not_permitted"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWireValue(value: String?): MediaSyncDeletionOutcome? =
            entries.firstOrNull { it.wireValue == value }
    }
}

interface MediaSyncDeletionExecutor {
    fun delete(name: String): MediaSyncDeletionOutcome
}

/**
 * Best-effort delete-after-sync on the glasses.
 *
 * Honest about the constraint: the capture belongs to Rokid's camera app, and under scoped
 * storage a plain app cannot delete another app's media without either `MANAGE_EXTERNAL_STORAGE`
 * or an interactive `createDeleteRequest` consent dialog — neither of which the headless hub has.
 * Both routes are attempted and the real outcome is reported all the way to the phone UI, which
 * disables the toggle's promise rather than pretending the file is gone.
 */
class AndroidMediaSyncDeletionExecutor(
    context: Context,
    private val catalog: MediaCatalog,
    private val logger: (String) -> Unit = {},
) : MediaSyncDeletionExecutor {
    private val appContext = context.applicationContext

    override fun delete(name: String): MediaSyncDeletionOutcome {
        val file = catalog.resolve(name) ?: return MediaSyncDeletionOutcome.ALREADY_GONE
        val direct = runCatching { file.delete() }
            .onFailure { logger("mediaSync delete file failed name=$name error=${it.message}") }
            .getOrDefault(false)
        if (direct && !file.exists()) {
            catalog.forget(name)
            return MediaSyncDeletionOutcome.DELETED
        }
        val viaMediaStore = deleteThroughMediaStore(name, file)
        if (viaMediaStore == MediaSyncDeletionOutcome.DELETED) return viaMediaStore
        return deleteThroughBridge(name, file, viaMediaStore)
    }

    /**
     * Last resort, and on this ROM the only one that works: the capture belongs to the camera app,
     * so both routes above are refused, while the shell-uid command bridge may remove it. The
     * bridge is best-effort by design — when it is not running the outcome stays the honest
     * [MediaSyncDeletionOutcome.NOT_PERMITTED] the settings screen already reports.
     */
    private fun deleteThroughBridge(
        name: String,
        file: File,
        fallback: MediaSyncDeletionOutcome,
    ): MediaSyncDeletionOutcome {
        // A shell process cannot survive a reboot, so once the arm epoch says the device rebooted
        // the submit can only burn its full response timeout per file — a twenty-photo sync would
        // stall for minutes proving the same absence twenty times.
        if (SelfArmBridgeLivenessStore.presumedDead(appContext)) {
            SelfArmBridgeLivenessStore.noteBridgeDemandUnanswered()
            logger("mediaSync delete bridge skipped name=$name reason=presumed_dead")
            return fallback
        }
        val deleted = runCatching { SelfArmCommandBridgeClient.deleteCapture(appContext, name) }
            .onFailure { logger("mediaSync delete bridge failed name=$name error=${it.message}") }
            .getOrDefault(false)
        if (!deleted || file.exists()) {
            // The one component that can delete this file did not deliver; remember the unmet
            // demand so the re-arm watcher treats the next radio opportunity as a reason to
            // revive the bridge.
            SelfArmBridgeLivenessStore.noteBridgeDemandUnanswered()
            logger("mediaSync delete bridge unanswered name=$name outcome=${fallback.wireValue}")
            return fallback
        }
        catalog.forget(name)
        logger("mediaSync delete name=$name via=bridge")
        return MediaSyncDeletionOutcome.DELETED
    }

    private fun deleteThroughMediaStore(name: String, file: File): MediaSyncDeletionOutcome {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val id = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(file.absolutePath),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.onFailure { logger("mediaSync delete lookup failed name=$name error=${it.message}") }
            .getOrNull()
            ?: return if (file.exists()) {
                MediaSyncDeletionOutcome.NOT_PERMITTED
            } else {
                MediaSyncDeletionOutcome.DELETED
            }
        val deleted = runCatching {
            resolver.delete(ContentUris.withAppendedId(collection, id), null, null) > 0
        }.onFailure {
            // RecoverableSecurityException lands here: it carries an IntentSender the hub has no
            // foreground to launch, so this is a genuine "cannot delete", not a transient error.
            logger("mediaSync delete mediastore refused name=$name error=${it.message}")
        }.getOrDefault(false)
        return when {
            deleted && !file.exists() -> {
                catalog.forget(name)
                MediaSyncDeletionOutcome.DELETED
            }
            file.exists() -> MediaSyncDeletionOutcome.NOT_PERMITTED
            else -> MediaSyncDeletionOutcome.FAILED
        }
    }
}
