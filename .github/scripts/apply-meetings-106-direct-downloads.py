from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
EXPORTER = SRC / "AssistantMeetingExternalArchive.kt"
ACTIVITY = SRC / "AssistantMeetingsActivity.kt"

# Meetings 1.0.5 already creates a durable private vault and calls sync() after audio finalization,
# protocol generation, and archive refresh. 1.0.6 changes only the public destination:
# files are written directly into the phone's Downloads root instead of a nested folder.
EXPORTER.write_text(r'''package com.anezium.rokidbus.plugin.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * User-visible mirror of the canonical app-private Meetings Vault.
 *
 * Completed artifacts are intentionally written straight into the Downloads root. This avoids
 * OEM/file-manager inconsistencies around app-created nested folders and makes the recording
 * immediately visible to the user. The private vault remains the crash-safe source of truth.
 */
internal class AssistantMeetingExternalArchive(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun sync(meetingId: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !MEETING_ID.matches(meetingId)) return 0
        val directory = File(
            File(appContext.filesDir, AssistantMeetingStore.ARCHIVE_DIR_NAME),
            meetingId,
        )
        if (!directory.isDirectory) return 0

        val prefix = "BuildGround_Meeting_" + meetingId
        val relativePath = Environment.DIRECTORY_DOWNLOADS
        val artifacts = listOf(
            Artifact(AssistantMeetingStore.SESSION_JSON_NAME, prefix + "_session.json", "application/json"),
            Artifact("audio.wav", prefix + ".wav", "audio/wav"),
            Artifact(
                AssistantMeetingStore.TRANSCRIPT_TEXT_NAME,
                prefix + "_transcript.txt",
                "text/plain",
            ),
            Artifact(
                AssistantMeetingStore.PROTOCOL_MARKDOWN_NAME,
                prefix + "_protocol.md",
                "text/markdown",
            ),
        )

        var exported = 0
        artifacts.forEach { artifact ->
            val source = File(directory, artifact.sourceName)
            if (!source.isFile || source.length() <= 0L) return@forEach
            if (writeArtifact(source, artifact, relativePath)) exported += 1
        }
        return exported
    }

    private fun writeArtifact(source: File, artifact: Artifact, relativePath: String): Boolean =
        runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(artifact.displayName, normalizeRelativePath(relativePath)),
            )

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, artifact.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Cannot create Downloads entry")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open Downloads stream")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                true
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }.onFailure { error ->
            Log.w(TAG, "Direct Downloads export failed for ${artifact.displayName}", error)
        }.getOrDefault(false)

    private fun normalizeRelativePath(path: String): String =
        if (path.endsWith('/')) path else path + "/"

    private data class Artifact(
        val sourceName: String,
        val displayName: String,
        val mime: String,
    )

    companion object {
        private const val TAG = "MeetingDownloads"
        private val MEETING_ID = Regex("m_[a-z0-9]{8}")
    }
}
''', encoding="utf-8")

activity = ACTIVITY.read_text(encoding="utf-8")
old = (
    '                    "Meetings Vault keeps every session on this phone. Completed files are also mirrored to " +\n'
    '                        "Downloads/BuildGround/Meetings/<meeting-id>. Audio remains visible even when transcription fails.",\n'
)
new = (
    '                    "Meetings Vault keeps every session on this phone. Completed files are saved directly into " +\n'
    '                        "Downloads as BuildGround_Meeting_<meeting-id>.wav. Audio remains independent from transcription.",\n'
)
if activity.count(old) != 1:
    raise SystemExit("Meetings 1.0.6 archive description marker changed unexpectedly")
ACTIVITY.write_text(activity.replace(old, new, 1), encoding="utf-8")

# Generated-runtime invariants.
exporter = EXPORTER.read_text(encoding="utf-8")
activity = ACTIVITY.read_text(encoding="utf-8")
for marker in (
    "MediaStore.Downloads.EXTERNAL_CONTENT_URI",
    'val relativePath = Environment.DIRECTORY_DOWNLOADS',
    'prefix + ".wav"',
    'prefix + "_transcript.txt"',
    'prefix + "_protocol.md"',
    'Log.w(TAG, "Direct Downloads export failed',
):
    if marker not in exporter:
        raise SystemExit(f"Meetings 1.0.6 exporter marker missing: {marker}")
if "Downloads as BuildGround_Meeting_<meeting-id>.wav" not in activity:
    raise SystemExit("Meetings 1.0.6 archive UI marker missing")
if "BuildGround/Meetings/" in exporter:
    raise SystemExit("Meetings 1.0.6 must not use a nested Downloads folder")
