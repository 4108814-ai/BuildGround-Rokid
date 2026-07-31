package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import com.anezium.rokidbus.shared.MediaSyncItem
import com.anezium.rokidbus.shared.MediaSyncMediaFile
import java.io.File

/**
 * The glasses' capture directory, gated for stability.
 *
 * Rokid's native camera button writes into `/sdcard/DCIM/Camera/` (photos observed as
 * `img-YYYYMMDD-HHMMSS-…jpg`; videos land beside them). Selection is by media extension rather
 * than filename prefix so a firmware change to the video prefix does not silently stop sync.
 */
class MediaCatalog(
    private val directory: File = File(DEFAULT_DIRECTORY),
    private val gate: MediaSyncStabilityGate = MediaSyncStabilityGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Scans the capture directory and returns the transfer-eligible entries, oldest capture
     * first, capped at [MediaSyncCatalogContract.MAX_ITEMS]. `truncated` tells the phone another
     * session is worth running immediately after this one.
     */
    fun scan(): CatalogScan {
        val listed = runCatching { directory.listFiles() }.getOrNull().orEmpty()
        val samples = listed.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val name = file.name
            if (!MediaSyncCatalogContract.isSafeName(name)) return@mapNotNull null
            if (!MediaSyncMediaFile.isSupported(name)) return@mapNotNull null
            MediaFileSample(name, file.length(), file.lastModified())
        }
        val eligible = gate.observe(samples, clock())
            .sortedWith(compareBy(MediaFileSample::modifiedMillis, MediaFileSample::name))
        val items = eligible.take(MediaSyncCatalogContract.MAX_ITEMS)
            .map { MediaSyncItem(it.name, it.sizeBytes, it.modifiedMillis) }
        return CatalogScan(
            items = items,
            truncated = eligible.size > items.size,
            // A new capture can be settling beside older, already-eligible files. Treating the
            // whole catalog as settled merely because one old file is eligible loses the only
            // follow-up scan for the new capture.
            settling = eligible.size < samples.size,
        )
    }

    /** Resolves a requested name inside the capture directory, refusing anything that escapes it. */
    fun resolve(name: String): File? {
        if (!MediaSyncCatalogContract.isSafeName(name)) return null
        if (!MediaSyncMediaFile.isSupported(name)) return null
        val file = File(directory, name)
        if (!file.isFile) return null
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return null
        val expected = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        return if (parent == expected) file else null
    }

    fun forget(name: String) = gate.forget(name)

    data class CatalogScan(
        val items: List<MediaSyncItem>,
        val truncated: Boolean,
        /**
         * At least one capture has not cleared the stability gate yet. This remains true when a
         * fresh capture sits beside older eligible files, so the engine re-checks once the
         * settling window has passed instead of waiting for another external trigger.
         */
        val settling: Boolean = false,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    companion object {
        const val DEFAULT_DIRECTORY = "/sdcard/DCIM/Camera"
    }
}
