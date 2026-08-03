package com.anezium.rokidbus.phone.mediasync

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.anezium.rokidbus.shared.MediaSyncMediaFile
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import java.io.Closeable
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Where a synced capture lands on the phone.
 *
 * `Download/Hi Rokid/` is not an arbitrary choice: Hi Rokid's own manual imports land in exactly
 * that folder with exactly these filenames, so synced captures join the same gallery bucket
 * instead of creating a second, competing album.
 */
object MediaSyncGalleryTarget {
    const val RELATIVE_PATH = "Download/Hi Rokid/"

    fun mimeType(name: String): String = MediaSyncMediaFile.mimeType(name)

    fun capturedAtMillis(name: String, fallbackMillis: Long): Long =
        MediaSyncMediaFile.capturedAtMillis(name) ?: fallbackMillis
}

interface MediaSyncGalleryWriter {
    /** True when a file with this capture name is already published in the target folder. */
    fun alreadyPublished(name: String, sizeBytes: Long): Boolean

    /** Null when the row could not be created; the caller reports the file as failed. */
    fun open(name: String): MediaSyncGalleryTransfer?
}

interface MediaSyncGalleryTransfer : Closeable {
    fun append(buffer: ByteArray, length: Int)

    /**
     * Publishes the pending row only when the bytes hash to [expectedSha256]. A mismatch discards
     * the row so a corrupt transfer never reaches the gallery and never gets acked.
     */
    fun publish(expectedSha256: String, capturedAtMillis: Long): Boolean

    fun discard()
}

class AndroidMediaSyncGalleryWriter internal constructor(
    private val contentResolver: ContentResolver,
    private val logger: (String) -> Unit = {},
) : MediaSyncGalleryWriter {
    constructor(
        context: Context,
        logger: (String) -> Unit = {},
    ) : this(context.applicationContext.contentResolver, logger)

    private val collection: Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun alreadyPublished(name: String, sizeBytes: Long): Boolean = runCatching {
        contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(name, MediaSyncGalleryTarget.RELATIVE_PATH),
            null,
        )?.use { cursor ->
            // EXIF enrichment changes the published byte count, but the glasses capture name is
            // stable across old untouched imports and newly enriched files.
            cursor.moveToFirst()
        } ?: false
    }.onFailure { logger("mediaSync gallery lookup failed name=$name error=${it.message}") }
        .getOrDefault(false)

    override fun open(name: String): MediaSyncGalleryTransfer? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MediaSyncGalleryTarget.mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaSyncGalleryTarget.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { contentResolver.insert(collection, values) }
            .onFailure { logger("mediaSync gallery insert failed name=$name error=${it.message}") }
            .getOrNull()
            ?: return null
        val stream = runCatching { contentResolver.openOutputStream(uri, "w") }
            .onFailure { logger("mediaSync gallery open failed name=$name error=${it.message}") }
            .getOrNull()
        if (stream == null) {
            runCatching { contentResolver.delete(uri, null, null) }
            return null
        }
        return AndroidMediaSyncGalleryTransfer(
            contentResolver,
            uri,
            MediaSyncGalleryTarget.mimeType(name),
            stream,
            logger,
        )
    }
}

private class AndroidMediaSyncGalleryTransfer(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val mimeType: String,
    private val stream: OutputStream,
    private val logger: (String) -> Unit,
) : MediaSyncGalleryTransfer {
    private val digest: MessageDigest = MediaSyncTransferContract.newDigest()
    private var finished = false

    override fun append(buffer: ByteArray, length: Int) {
        stream.write(buffer, 0, length)
        digest.update(buffer, 0, length)
    }

    override fun publish(expectedSha256: String, capturedAtMillis: Long): Boolean {
        if (finished) return false
        finished = true
        val actual = runCatching {
            stream.flush()
            stream.close()
            MediaSyncTransferContract.hex(digest)
        }.onFailure { logger("mediaSync gallery flush failed error=${it.message}") }.getOrNull()
        if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
            logger("mediaSync gallery checksum mismatch uri=$uri")
            deleteRow()
            return false
        }
        writeMissingCaptureTimestamp(capturedAtMillis)
        val published = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, capturedAtMillis / 1000L)
            }
            contentResolver.update(uri, values, null, null) > 0
        }.onFailure { logger("mediaSync gallery publish failed error=${it.message}") }
            .getOrDefault(false)
        if (!published) deleteRow()
        return published
    }

    override fun discard() {
        if (finished) return
        finished = true
        runCatching { stream.close() }
        deleteRow()
    }

    override fun close() = discard()

    private fun writeMissingCaptureTimestamp(capturedAtMillis: Long) {
        if (mimeType != "image/jpeg") return
        runCatching {
            val descriptor = contentResolver.openFileDescriptor(uri, "rw")
                ?: error("file descriptor unavailable")
            descriptor.use {
                val exif = ExifInterface(it.fileDescriptor)
                if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getDefault()
                    }
                    exif.setAttribute(
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        formatter.format(Date(capturedAtMillis)),
                    )
                    exif.saveAttributes()
                }
            }
        }.onFailure { logger("mediaSync gallery EXIF update failed uri=$uri error=${it.message}") }
    }

    private fun deleteRow() {
        runCatching { contentResolver.delete(uri, null, null) }
            .onFailure { logger("mediaSync gallery cleanup failed uri=$uri error=${it.message}") }
    }
}
