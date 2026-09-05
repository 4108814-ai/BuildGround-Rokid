from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / ".github/scripts/apply-meetings-104.py"
SRC = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant"
STORE = SRC / "AssistantMeetingStore.kt"
AUDIO = SRC / "AssistantMeetingAudioRecorder.kt"
ACTIVITY = SRC / "AssistantMeetingsActivity.kt"
SERVICE = SRC / "AssistantPluginService.kt"
EXPORTER = SRC / "AssistantMeetingExternalArchive.kt"

runpy.run_path(str(BASE), run_name="__main__")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# --------------------------------------------------------------------------- canonical vault manifest
# A meeting now gets a durable archive shell at Start. Transcript generation is no longer the
# index of truth: session.json is. This makes audio-only and recovered recordings visible.
replace_once(
    STORE,
    "            val id = allocateMeetingId()\n"
    "            val header = JSONObject()\n",
    "            val id = allocateMeetingId()\n"
    "            val directory = File(archiveRoot, id)\n"
    "            if (!directory.isDirectory && !directory.mkdirs()) {\n"
    "                error(\"Assistant meeting vault directory could not be created.\")\n"
    "            }\n"
    "            if (!writeSessionManifest(directory, id, startedAt, null, STATUS_RECORDING)) {\n"
    "                error(\"Assistant meeting session manifest could not be created.\")\n"
    "            }\n"
    "            val header = JSONObject()\n",
    "create vault shell at start",
)

replace_once(
    STORE,
    "            if (!directory.isDirectory && !directory.mkdirs()) {\n"
    "                error(\"Assistant meeting archive directory could not be created.\")\n"
    "            }\n"
    "            val transcriptJson = JSONObject()\n",
    "            if (!directory.isDirectory && !directory.mkdirs()) {\n"
    "                error(\"Assistant meeting archive directory could not be created.\")\n"
    "            }\n"
    "            writeSessionManifest(\n"
    "                directory,\n"
    "                id,\n"
    "                transcript.startedAt,\n"
    "                transcript.finishedAt,\n"
    "                STATUS_COMPLETED,\n"
    "            )\n"
    "            val transcriptJson = JSONObject()\n",
    "complete vault manifest before transcript",
)

replace_once(
    STORE,
    "        val transcriptFile = File(directory, TRANSCRIPT_JSON_NAME)\n"
    "        if (!transcriptFile.isFile) return null\n",
    "        val transcriptFile = File(directory, TRANSCRIPT_JSON_NAME)\n"
    "        if (!transcriptFile.isFile) return readSessionArchive(directory)\n",
    "manifest fallback for archive reader",
)

vault_helpers = r'''    fun ensureRecoveredAudioMeeting(
        meetingId: String,
        pcmBytes: Long,
        modifiedAtMs: Long,
    ): Boolean = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        val directory = File(archiveRoot, meetingId)
        if (!directory.isDirectory && !directory.mkdirs()) return@synchronized false
        val manifest = File(directory, SESSION_JSON_NAME)
        val existingStatus = runCatching {
            if (!manifest.isFile) null else JSONObject(manifest.readText(Charsets.UTF_8))
                .optString(JSON_STATUS)
                .takeIf(String::isNotBlank)
        }.getOrNull()
        if (existingStatus != null && existingStatus != STATUS_RECORDING) return@synchronized true

        val safeModified = modifiedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val finishedAt = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(safeModified),
            java.time.ZoneId.systemDefault(),
        )
        val durationMs = if (pcmBytes <= 0L) 0L else
            ((pcmBytes * 1_000L) / RECOVERY_PCM_BYTES_PER_SECOND).coerceAtLeast(0L)
        val startedAt = finishedAt.minus(java.time.Duration.ofMillis(durationMs))
        writeSessionManifest(
            directory = directory,
            id = meetingId,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = STATUS_RECOVERED,
        )
    }

    fun repairAudioOnlyArchives(): Int = synchronized(fileLock) {
        if (!archiveRoot.isDirectory) return@synchronized 0
        var repaired = 0
        archiveRoot.listFiles()
            ?.filter { directory -> directory.isDirectory && MEETING_ID.matches(directory.name) }
            ?.forEach { directory ->
                val transcript = File(directory, TRANSCRIPT_JSON_NAME)
                val audio = File(directory, "audio.wav")
                if (transcript.isFile || !audio.isFile || audio.length() <= 44L) return@forEach
                val pcmBytes = (audio.length() - 44L).coerceAtLeast(0L)
                if (ensureRecoveredAudioMeeting(directory.name, pcmBytes, audio.lastModified())) {
                    repaired += 1
                }
            }
        repaired
    }

    private fun readSessionArchive(directory: File): AssistantMeetingArchive? {
        val manifest = File(directory, SESSION_JSON_NAME)
        if (!manifest.isFile) return null
        return runCatching {
            val value = JSONObject(manifest.readText(Charsets.UTF_8))
            val id = value.getString(JSON_ID)
            if (id != directory.name || !MEETING_ID.matches(id)) return@runCatching null
            val status = value.optString(JSON_STATUS)
            if (status == STATUS_RECORDING) return@runCatching null
            val startedAt = ZonedDateTime.parse(value.getString(JSON_STARTED_AT))
            val finishedText = value.optString(JSON_FINISHED_AT)
                .takeIf { it.isNotBlank() && it != "null" }
            val fallbackMillis = listOf(
                File(directory, "audio.wav").lastModified(),
                manifest.lastModified(),
            ).maxOrNull()?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val finishedAt = finishedText?.let(ZonedDateTime::parse) ?: ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(fallbackMillis),
                java.time.ZoneId.systemDefault(),
            )
            val protocol = File(directory, PROTOCOL_MARKDOWN_NAME)
                .takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            AssistantMeetingArchive(
                id = id,
                startedAt = startedAt,
                finishedAt = finishedAt,
                segments = emptyList(),
                protocol = protocol,
            )
        }.onFailure(::logFailure).getOrNull()
    }

    private fun writeSessionManifest(
        directory: File,
        id: String,
        startedAt: ZonedDateTime,
        finishedAt: ZonedDateTime?,
        status: String,
    ): Boolean = runCatching {
        val body = JSONObject()
            .put(JSON_VERSION, STORE_VERSION)
            .put(JSON_ID, id)
            .put(JSON_STARTED_AT, startedAt.toString())
            .put(JSON_FINISHED_AT, finishedAt?.toString() ?: JSONObject.NULL)
            .put(JSON_STATUS, status)
        writeAssistantJsonAtomically(
            File(directory, SESSION_JSON_NAME),
            body.toString(2),
            fileOperations,
        )
        true
    }.onFailure(::logFailure).getOrDefault(false)

'''
replace_once(
    STORE,
    "    private val fileLock: Any\n",
    vault_helpers + "    private val fileLock: Any\n",
    "vault helpers",
)

replace_once(
    STORE,
    '        internal const val PROTOCOL_MARKDOWN_NAME = "protocol.md"\n',
    '        internal const val PROTOCOL_MARKDOWN_NAME = "protocol.md"\n'
    '        internal const val SESSION_JSON_NAME = "session.json"\n',
    "session manifest filename",
)
replace_once(
    STORE,
    '        private const val JSON_FINISHED_AT = "finishedAt"\n',
    '        private const val JSON_FINISHED_AT = "finishedAt"\n'
    '        private const val JSON_STATUS = "status"\n'
    '        private const val STATUS_RECORDING = "recording"\n'
    '        private const val STATUS_COMPLETED = "completed"\n'
    '        private const val STATUS_RECOVERED = "recovered"\n'
    '        private const val RECOVERY_PCM_BYTES_PER_SECOND = 32_000L\n',
    "vault status constants",
)

# --------------------------------------------------------------------------- recovery of 1.0.4 orphan audio
replace_once(
    AUDIO,
    "                if (!MEETING_ID.matches(meetingId) || meetingId == activeId) return@forEach\n"
    "                if (spool.length() <= 0L || meetingStore.meeting(meetingId) == null) return@forEach\n"
    "                val result = meetingStore.saveMeetingAudioPcm(\n",
    "                if (!MEETING_ID.matches(meetingId) || meetingId == activeId) return@forEach\n"
    "                if (spool.length() <= 0L) return@forEach\n"
    "                if (meetingStore.meeting(meetingId) == null) {\n"
    "                    meetingStore.ensureRecoveredAudioMeeting(\n"
    "                        meetingId = meetingId,\n"
    "                        pcmBytes = spool.length(),\n"
    "                        modifiedAtMs = spool.lastModified(),\n"
    "                    )\n"
    "                }\n"
    "                if (meetingStore.meeting(meetingId) == null) return@forEach\n"
    "                val result = meetingStore.saveMeetingAudioPcm(\n",
    "recover orphan raw spool",
)

# --------------------------------------------------------------------------- user-visible mirror
EXPORTER.write_text(r'''package com.anezium.rokidbus.plugin.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Best-effort user-visible mirror of the canonical app-private Meetings Vault.
 * Canonical data never depends on this export succeeding.
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
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/BuildGround/Meetings/" + meetingId + "/"
        val artifacts = listOf(
            Artifact(AssistantMeetingStore.SESSION_JSON_NAME, "application/json"),
            Artifact("audio.wav", "audio/wav"),
            Artifact(AssistantMeetingStore.TRANSCRIPT_TEXT_NAME, "text/plain"),
            Artifact(AssistantMeetingStore.PROTOCOL_MARKDOWN_NAME, "text/markdown"),
        )
        var exported = 0
        artifacts.forEach { artifact ->
            val source = File(directory, artifact.name)
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
                arrayOf(artifact.name, relativePath),
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name)
                put(MediaStore.MediaColumns.MIME_TYPE, artifact.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Cannot create export entry")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open export stream")
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
        }.getOrDefault(false)

    private data class Artifact(val name: String, val mime: String)

    companion object {
        private val MEETING_ID = Regex("m_[a-z0-9]{8}")
    }
}
''', encoding="utf-8")

# --------------------------------------------------------------------------- phone archive repairs + mirror sync
replace_once(
    ACTIVITY,
    "    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }\n"
    "    private lateinit var listColumn: LinearLayout\n",
    "    private val meetingStore by lazy { AssistantMeetingStore(applicationContext) }\n"
    "    private val meetingAudioRecovery by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n"
    "    private val meetingExternalArchive by lazy {\n"
    "        AssistantMeetingExternalArchive(applicationContext)\n"
    "    }\n"
    "    private lateinit var listColumn: LinearLayout\n",
    "archive recovery helpers",
)

replace_once(
    ACTIVITY,
    "        Thread {\n"
    "            val meetings = runCatching { meetingStore.meetings() }.getOrDefault(emptyList())\n",
    "        Thread {\n"
    "            runCatching { meetingStore.repairAudioOnlyArchives() }\n"
    "            val activeId = runCatching { meetingStore.loadActive()?.id }.getOrNull()\n"
    "            runCatching { meetingAudioRecovery.recoverCompleted(activeId) }\n"
    "            val meetings = runCatching { meetingStore.meetings() }.getOrDefault(emptyList())\n"
    "            meetings.forEach { meeting ->\n"
    "                runCatching { meetingExternalArchive.sync(meeting.id) }\n"
    "            }\n",
    "archive repair before list",
)

replace_once(
    ACTIVITY,
    '                    "Meeting briefs and full transcripts are saved on this phone only. " +\n'
    '                        "Open a meeting to read, copy, share, or delete it.",\n',
    '                    "Meetings Vault keeps every session on this phone. Completed files are also mirrored to " +\n'
    '                        "Downloads/BuildGround/Meetings/<meeting-id>. Audio remains visible even when transcription fails.",\n',
    "vault explanation",
)

replace_once(
    ACTIVITY,
    '        val brief = if (meeting.hasProtocol) "brief ready" else "transcript only"\n'
    '        val audio = if (meeting.hasAudio) "audio ready" else "no audio"\n'
    '        val fragments = if (meeting.segmentCount == 1) "1 fragment" else "${meeting.segmentCount} fragments"\n'
    '        return "$audio · $brief · $fragments · ${duration} min"\n',
    '        val textState = when {\n'
    '            meeting.hasProtocol -> "protocol ready"\n'
    '            meeting.segmentCount > 0 -> "transcript ready"\n'
    '            else -> "no transcript"\n'
    '        }\n'
    '        val audio = if (meeting.hasAudio) "audio ready" else "no audio"\n'
    '        val fragments = if (meeting.segmentCount == 1) "1 fragment" else "${meeting.segmentCount} fragments"\n'
    '        return "$audio · $textState · $fragments · ${duration} min"\n',
    "archive status wording",
)

# --------------------------------------------------------------------------- service sync after source finalization and protocol generation
replace_once(
    SERVICE,
    "    private val meetingAudioRecorder by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n"
    "    private val meetingAudioSegmenter = AssistantMeetingAudioSegmenter()\n",
    "    private val meetingAudioRecorder by lazy {\n"
    "        AssistantMeetingAudioRecorder(applicationContext, meetingStore)\n"
    "    }\n"
    "    private val meetingExternalArchive by lazy {\n"
    "        AssistantMeetingExternalArchive(applicationContext)\n"
    "    }\n"
    "    private val meetingAudioSegmenter = AssistantMeetingAudioSegmenter()\n",
    "service external archive",
)

replace_once(
    SERVICE,
    "            val audio = meeting?.id?.let { meetingId ->\n"
    "                withContext(Dispatchers.IO) { meetingAudioRecorder.finish(meetingId) }\n"
    "            }\n"
    "            if (meeting == null) {\n",
    "            val audio = meeting?.id?.let { meetingId ->\n"
    "                withContext(Dispatchers.IO) { meetingAudioRecorder.finish(meetingId) }\n"
    "            }\n"
    "            if (meeting != null) {\n"
    "                withContext(Dispatchers.IO) { meetingExternalArchive.sync(meeting.id.orEmpty()) }\n"
    "            }\n"
    "            if (meeting == null) {\n",
    "sync completed meeting vault",
)

replace_once(
    SERVICE,
    "                            meetingStore.saveProtocol(meetingProtocolId, finalAnswer.orEmpty())\n",
    "                            if (meetingStore.saveProtocol(meetingProtocolId, finalAnswer.orEmpty())) {\n"
    "                                meetingExternalArchive.sync(meetingProtocolId)\n"
    "                            }\n",
    "sync protocol artifact",
)

# --------------------------------------------------------------------------- generated-runtime invariants
store = STORE.read_text(encoding="utf-8")
audio = AUDIO.read_text(encoding="utf-8")
activity = ACTIVITY.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")
exporter = EXPORTER.read_text(encoding="utf-8")

for marker in (
    'SESSION_JSON_NAME = "session.json"',
    "ensureRecoveredAudioMeeting(",
    "repairAudioOnlyArchives()",
    "readSessionArchive(directory)",
    "STATUS_RECORDING",
    "STATUS_RECOVERED",
):
    if marker not in store:
        raise SystemExit(f"Meetings 1.0.5 store marker missing: {marker}")
for marker in (
    "meetingStore.ensureRecoveredAudioMeeting(",
    "meetingStore.meeting(meetingId) == null",
):
    if marker not in audio:
        raise SystemExit(f"Meetings 1.0.5 audio recovery marker missing: {marker}")
for marker in (
    "Downloads/BuildGround/Meetings/<meeting-id>",
    "meetingAudioRecovery.recoverCompleted(activeId)",
    "meetingExternalArchive.sync(meeting.id)",
    'else -> "no transcript"',
):
    if marker not in activity:
        raise SystemExit(f"Meetings 1.0.5 archive UI marker missing: {marker}")
for marker in (
    "AssistantMeetingExternalArchive(applicationContext)",
    "meetingExternalArchive.sync(meetingProtocolId)",
):
    if marker not in service:
        raise SystemExit(f"Meetings 1.0.5 service marker missing: {marker}")
for marker in (
    "MediaStore.Downloads.EXTERNAL_CONTENT_URI",
    "BuildGround/Meetings/",
    "audio.wav",
    "protocol.md",
):
    if marker not in exporter:
        raise SystemExit(f"Meetings 1.0.5 exporter marker missing: {marker}")
