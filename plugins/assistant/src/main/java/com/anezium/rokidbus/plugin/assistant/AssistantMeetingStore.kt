package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

internal data class AssistantMeetingDraft(
    val id: String,
    val startedAt: ZonedDateTime,
    val segments: List<String>,
)

internal interface AssistantMeetingPersistence {
    fun loadActive(): AssistantMeetingDraft?
    fun start(startedAt: ZonedDateTime): String?
    fun append(meetingId: String?, segment: String)
    fun complete(transcript: AssistantMeetingTranscript): String?
    fun cancel(meetingId: String?)
}

internal object NoopAssistantMeetingPersistence : AssistantMeetingPersistence {
    override fun loadActive(): AssistantMeetingDraft? = null
    override fun start(startedAt: ZonedDateTime): String? = null
    override fun append(meetingId: String?, segment: String) = Unit
    override fun complete(transcript: AssistantMeetingTranscript): String? = transcript.id
    override fun cancel(meetingId: String?) = Unit
}

/**
 * App-private, crash-tolerant meeting persistence.
 *
 * The active meeting is append-only JSONL so every recognized fragment is durable without
 * rewriting the whole transcript. Finishing a meeting creates a stable archive directory with
 * transcript.json, transcript.txt and, once AI summarization succeeds, protocol.md.
 */
internal class AssistantMeetingStore internal constructor(
    filesDir: File,
    private val idGenerator: () -> String = { assistantShortId(MEETING_ID_PREFIX) },
    private val fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    private val logger: (String) -> Unit = {},
) : AssistantMeetingPersistence {
    private val activeFile = File(filesDir, ACTIVE_FILE_NAME)
    private val archiveRoot = File(filesDir, ARCHIVE_DIR_NAME)

    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        logger = { message -> Log.w(TAG, message) },
    )

    override fun loadActive(): AssistantMeetingDraft? = synchronized(fileLock) {
        readActive()
    }

    override fun start(startedAt: ZonedDateTime): String? = synchronized(fileLock) {
        runCatching {
            val id = allocateMeetingId()
            val header = JSONObject()
                .put(JSON_VERSION, STORE_VERSION)
                .put(JSON_TYPE, TYPE_HEADER)
                .put(JSON_ID, id)
                .put(JSON_STARTED_AT, startedAt.toString())
            writeAssistantJsonAtomically(
                target = activeFile,
                text = header.toString() + "\n",
                fileOperations = fileOperations,
            )
            id
        }.onFailure(::logFailure).getOrNull()
    }

    override fun append(meetingId: String?, segment: String) = synchronized(fileLock) {
        if (meetingId == null || !MEETING_ID.matches(meetingId)) return@synchronized
        val normalized = segment.trim().take(MAX_SEGMENT_CHARS)
        if (normalized.isBlank()) return@synchronized
        val active = readActive() ?: return@synchronized
        if (active.id != meetingId) return@synchronized
        runCatching {
            val line = JSONObject()
                .put(JSON_TYPE, TYPE_SEGMENT)
                .put(JSON_TEXT, normalized)
                .toString() + "\n"
            FileOutputStream(activeFile, true).use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        }.onFailure(::logFailure)
    }

    override fun complete(transcript: AssistantMeetingTranscript): String? = synchronized(fileLock) {
        val id = transcript.id?.takeIf(MEETING_ID::matches) ?: return@synchronized null
        runCatching {
            val directory = File(archiveRoot, id)
            if (!directory.isDirectory && !directory.mkdirs()) {
                error("Assistant meeting archive directory could not be created.")
            }
            val transcriptJson = JSONObject()
                .put(JSON_VERSION, STORE_VERSION)
                .put(JSON_ID, id)
                .put(JSON_STARTED_AT, transcript.startedAt.toString())
                .put(JSON_FINISHED_AT, transcript.finishedAt.toString())
                .put(JSON_SEGMENTS, org.json.JSONArray(transcript.segments))
            writeAssistantJsonAtomically(
                File(directory, TRANSCRIPT_JSON_NAME),
                transcriptJson.toString(2),
                fileOperations,
            )
            writeAssistantJsonAtomically(
                File(directory, TRANSCRIPT_TEXT_NAME),
                transcript.asPlainText(),
                fileOperations,
            )
            deleteActiveIfSameMeeting(id)
            id
        }.onFailure(::logFailure).getOrNull()
    }

    fun saveProtocol(meetingId: String, protocol: String): Boolean = synchronized(fileLock) {
        if (!MEETING_ID.matches(meetingId)) return@synchronized false
        val body = protocol.trim()
        if (body.isBlank()) return@synchronized false
        val directory = File(archiveRoot, meetingId)
        if (!directory.isDirectory) return@synchronized false
        runCatching {
            writeAssistantJsonAtomically(
                File(directory, PROTOCOL_MARKDOWN_NAME),
                body + "\n",
                fileOperations,
            )
            true
        }.onFailure(::logFailure).getOrDefault(false)
    }

    override fun cancel(meetingId: String?) = synchronized(fileLock) {
        if (meetingId == null) return@synchronized
        deleteActiveIfSameMeeting(meetingId)
    }

    private val fileLock: Any
        get() = FILE_LOCKS.computeIfAbsent(activeFile.absoluteFile.normalize().path) { Any() }

    private fun readActive(): AssistantMeetingDraft? {
        if (!activeFile.isFile) return null
        return runCatching {
            val lines = activeFile.readLines(Charsets.UTF_8)
            if (lines.isEmpty()) return@runCatching null
            val header = JSONObject(lines.first())
            if (header.optInt(JSON_VERSION) != STORE_VERSION ||
                header.optString(JSON_TYPE) != TYPE_HEADER
            ) {
                return@runCatching null
            }
            val id = header.getString(JSON_ID)
            if (!MEETING_ID.matches(id)) return@runCatching null
            val startedAt = ZonedDateTime.parse(header.getString(JSON_STARTED_AT))
            val segments = buildList {
                lines.drop(1).forEach { line ->
                    if (line.isBlank() || size >= MAX_SEGMENTS) return@forEach
                    runCatching { JSONObject(line) }.getOrNull()
                        ?.takeIf { value -> value.optString(JSON_TYPE) == TYPE_SEGMENT }
                        ?.optString(JSON_TEXT)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { add(it.take(MAX_SEGMENT_CHARS)) }
                }
            }
            AssistantMeetingDraft(id = id, startedAt = startedAt, segments = segments)
        }.onFailure(::logFailure).getOrNull()
    }

    private fun allocateMeetingId(): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idGenerator()
            if (MEETING_ID.matches(candidate) && !File(archiveRoot, candidate).exists()) {
                return candidate
            }
        }
        error("Could not allocate a unique meeting id.")
    }

    private fun deleteActiveIfSameMeeting(meetingId: String) {
        val active = readActive() ?: return
        if (active.id == meetingId && activeFile.exists() && !activeFile.delete()) {
            logger("Assistant meeting active file could not be removed")
        }
    }

    private fun AssistantMeetingTranscript.asPlainText(): String = buildString {
        append("Meeting: ")
        append(id.orEmpty())
        append('\n')
        append("Started: ")
        append(startedAt)
        append('\n')
        append("Finished: ")
        append(finishedAt)
        append("\n\n")
        segments.forEachIndexed { index, segment ->
            append(index + 1)
            append(". ")
            append(segment)
            append('\n')
        }
    }

    private fun logFailure(error: Throwable) {
        logger("Assistant meeting store failed: ${error.javaClass.simpleName}")
    }

    companion object {
        internal const val ACTIVE_FILE_NAME = "assistant_meeting_active_v1.jsonl"
        internal const val ARCHIVE_DIR_NAME = "assistant_meetings"
        internal const val TRANSCRIPT_JSON_NAME = "transcript.json"
        internal const val TRANSCRIPT_TEXT_NAME = "transcript.txt"
        internal const val PROTOCOL_MARKDOWN_NAME = "protocol.md"
        private const val STORE_VERSION = 1
        private const val MEETING_ID_PREFIX = "m_"
        private const val MAX_ID_ATTEMPTS = 100
        private const val MAX_SEGMENTS = 500
        private const val MAX_SEGMENT_CHARS = 2_000
        private const val TAG = "NexusAssistant"
        private const val JSON_VERSION = "version"
        private const val JSON_TYPE = "type"
        private const val JSON_ID = "id"
        private const val JSON_STARTED_AT = "startedAt"
        private const val JSON_FINISHED_AT = "finishedAt"
        private const val JSON_SEGMENTS = "segments"
        private const val JSON_TEXT = "text"
        private const val TYPE_HEADER = "meeting"
        private const val TYPE_SEGMENT = "segment"
        private val MEETING_ID = Regex("m_[a-z0-9]{8}")
        private val FILE_LOCKS = ConcurrentHashMap<String, Any>()
    }
}
