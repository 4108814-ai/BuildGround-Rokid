package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class MediaSyncCaptureType(val wireValue: String) {
    PHOTO("photo"),
    PHOTO_AR("photo_ar"),
    VIDEO("video"),
    VIDEO_AR("video_ar");

    val isVideo: Boolean get() = this == VIDEO || this == VIDEO_AR
    val isAr: Boolean get() = this == PHOTO_AR || this == VIDEO_AR

    companion object {
        fun fromWireValue(value: String?): MediaSyncCaptureType? =
            entries.firstOrNull { it.wireValue == value }

        /** Fallback when a catalog carries no type (old glasses hub): derive from extension. */
        fun defaultFor(name: String): MediaSyncCaptureType =
            if (MediaSyncMediaFile.isVideo(name)) VIDEO else PHOTO

        fun of(name: String, ar: Boolean): MediaSyncCaptureType = when {
            MediaSyncMediaFile.isVideo(name) -> if (ar) VIDEO_AR else VIDEO
            else -> if (ar) PHOTO_AR else PHOTO
        }
    }
}

/** One media file the glasses offer for transfer, identified by its capture filename. */
data class MediaSyncItem(
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val captureType: MediaSyncCaptureType,
)

/**
 * Catalog exchanged on the media-sync data plane. Entries are capped per response so a
 * multi-thousand-file backfill degrades into several sessions instead of one huge frame.
 */
object MediaSyncCatalogContract {
    const val VERSION = 1
    const val MAX_ITEMS = 512
    const val MAX_NAME_LENGTH = 255
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024

    fun encode(items: List<MediaSyncItem>, truncated: Boolean): JSONObject {
        require(items.size <= MAX_ITEMS) { "Media sync catalog too large: ${items.size}" }
        return JSONObject()
            .put("version", VERSION)
            .put("truncated", truncated)
            .put(
                "items",
                JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject()
                                .put("name", item.name)
                                .put("size", item.sizeBytes)
                                .put("mtime", item.modifiedMillis)
                                .put("type", item.captureType.wireValue),
                        )
                    }
                },
            )
    }

    /** Returns null when the payload is malformed; a well-formed empty catalog decodes fine. */
    fun decode(payload: JSONObject): MediaSyncCatalog? {
        if (payload.optInt("version") != VERSION) return null
        val array = payload.optJSONArray("items") ?: return null
        if (array.length() > MAX_ITEMS) return null
        val items = ArrayList<MediaSyncItem>(array.length())
        val seen = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: return null
            val name = entry.optString("name")
            val size = entry.optLong("size", -1L)
            val modified = entry.optLong("mtime", -1L)
            if (!isSafeName(name) || size !in 0..MAX_FILE_BYTES || modified < 0L) return null
            if (!seen.add(name)) return null
            val captureType = MediaSyncCaptureType.fromWireValue(entry.optString("type"))
                ?: MediaSyncCaptureType.defaultFor(name)
            items += MediaSyncItem(name, size, modified, captureType)
        }
        return MediaSyncCatalog(items, payload.optBoolean("truncated", false))
    }

    /**
     * Capture filenames are used verbatim as phone-side display names and as the request key,
     * so anything that could escape the capture directory is rejected outright.
     */
    fun isSafeName(name: String): Boolean = safeNamePattern.matches(name)

    /**
     * Conservative allowlist rather than a blocklist: a capture name must start
     * alphanumerically, so `.`, `..` and dotfiles are out, and may then only carry
     * alphanumerics and `. _ + -`, which excludes separators, spaces and control
     * characters in one rule.
     */
    private val safeNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,254}")
}

data class MediaSyncCatalog(
    val items: List<MediaSyncItem>,
    val truncated: Boolean,
)

/**
 * Capture-file naming rules shared by both hubs: which extensions the sync engine carries and
 * how the Rokid capture filename encodes the capture instant
 * (`img-20260710-175956-a0-N1-2.jpg`, `vid-20260710-175956-….mp4`).
 */
object MediaSyncMediaFile {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "dng")
    private val videoExtensions = setOf("mp4", "mov", "3gp", "mkv", "webm")

    private val timestampPattern = Regex("(?:^|[-_])(\\d{8})-(\\d{6})(?:[-_.]|$)")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    fun isSupported(name: String): Boolean = extension(name).let {
        it in imageExtensions || it in videoExtensions
    }

    fun isVideo(name: String): Boolean = extension(name) in videoExtensions

    fun mimeType(name: String): String = when (extension(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "webp" -> "image/webp"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    /**
     * Capture instant parsed from the filename in the device's local time zone, or null when the
     * name carries no usable `YYYYMMDD-HHMMSS` group. Callers fall back to the file mtime.
     */
    fun capturedAtMillis(name: String, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        val match = timestampPattern.find(name) ?: return null
        val date = match.groupValues[1]
        val time = match.groupValues[2]
        val year = date.substring(0, 4).toInt()
        val month = date.substring(4, 6).toInt()
        val day = date.substring(6, 8).toInt()
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(2, 4).toInt()
        val second = time.substring(4, 6).toInt()
        if (year !in 2000..2199 || month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val calendar = Calendar.getInstance(timeZone, Locale.US).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return runCatching { calendar.timeInMillis }.getOrNull()
    }
}
