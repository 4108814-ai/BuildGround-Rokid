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
        return deleteThroughMediaStore(name, file)
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
