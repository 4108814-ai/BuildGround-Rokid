package com.anezium.rokidbus.phone.mediasync

import android.content.Context
import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Partially received captures, parked in the hub's private storage until they are whole.
 *
 * Resume could in principle append into the pending MediaStore row across sessions, but that was
 * rejected: `IS_PENDING` rows have their own lifecycle (the system reclaims them, and append-mode
 * descriptors are not uniformly supported by every provider), and a multi-minute video that loses
 * its partial every time the camera opens would never finish. A private file is boring and total:
 * its length *is* the resume offset, so there is no second bookkeeping to drift out of sync, it
 * survives process death, and the whole-file hash can be recomputed from it at any time.
 *
 * The cost is one extra copy at publish time, which is paid once per file and never on the link.
 */
internal class MediaSyncStagingStore(private val directory: File) {

    /** How many bytes of [name] are already held. Doubles as the resume offset. */
    fun receivedBytes(name: String): Long {
        val file = fileFor(name) ?: return 0L
        return if (file.isFile) file.length() else 0L
    }

    fun append(name: String, bytes: ByteArray, length: Int): Boolean {
        val file = fileFor(name) ?: return false
        return runCatching {
            directory.mkdirs()
            FileOutputStream(file, true).use { it.write(bytes, 0, length) }
            true
        }.getOrDefault(false)
    }

    /** Whole-file SHA-256 of what has been staged, or null when it cannot be read. */
    fun sha256(name: String): String? {
        val file = fileFor(name) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val digest = MediaSyncTransferContract.newDigest()
            file.inputStream().use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            MediaSyncTransferContract.hex(digest)
        }.getOrNull()
    }

    fun <T> readStaged(name: String, block: (InputStream) -> T): T? {
        val file = fileFor(name) ?: return null
        if (!file.isFile) return null
        return runCatching { file.inputStream().use(block) }.getOrNull()
    }

    fun discard(name: String) {
        fileFor(name)?.let { file -> runCatching { file.delete() } }
    }

    /** Drops everything: used when the ledger is reset, so no orphan can be adopted later. */
    fun discardAll() {
        runCatching { directory.listFiles() }.getOrNull()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun fileFor(name: String): File? {
        // Capture names are already restricted to an alphanumeric allowlist, so they are safe as
        // filenames; anything else never gets a staging file at all.
        if (!MediaSyncCatalogContract.isSafeName(name)) return null
        return File(directory, "$name$SUFFIX")
    }

    companion object {
        private const val DIRECTORY = "mediasync-staging"
        private const val SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024

        fun directoryFor(context: Context): File =
            File(context.applicationContext.filesDir, DIRECTORY)
    }
}
