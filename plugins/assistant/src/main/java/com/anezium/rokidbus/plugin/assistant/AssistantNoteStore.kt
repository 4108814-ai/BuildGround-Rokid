package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class AssistantNote(
    val id: String,
    val title: String,
    val text: String,
    val createdAtMs: Long,
)

sealed interface AssistantNoteSaveResult {
    data class Saved(
        val note: AssistantNote,
        val textTruncated: Boolean,
    ) : AssistantNoteSaveResult

    data object Full : AssistantNoteSaveResult
}

/** Blocking, thread-safe note persistence for tools and the future settings UI. */
class AssistantNoteStore internal constructor(
    filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxNotes: Int = MAX_NOTES,
    private val idGenerator: () -> String = { assistantShortId(NOTE_ID_PREFIX) },
    private val fileOperations: AssistantAtomicFileOperations = NioAssistantAtomicFileOperations,
    private val logger: (String) -> Unit = {},
) {
    private val storeFile: File = File(filesDir, STORE_FILE_NAME)

    constructor(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        filesDir = context.applicationContext.filesDir,
        clock = clock,
        logger = { message -> Log.w(TAG, message) },
    )

    init {
        require(maxNotes > 0)
    }

    fun notes(): List<AssistantNote> = synchronized(fileLock) {
        readNotes().sortedByDescending(AssistantNote::createdAtMs)
    }

    fun note(id: String): AssistantNote? = synchronized(fileLock) {
        readNotes().firstOrNull { it.id == id }
    }

    fun search(query: String): List<AssistantNote> = synchronized(fileLock) {
        val needle = query.trim()
        if (needle.isEmpty()) return@synchronized emptyList()
        readNotes()
            .filter { note ->
                note.title.contains(needle, ignoreCase = true) ||
                    note.text.contains(needle, ignoreCase = true)
            }
            .sortedByDescending(AssistantNote::createdAtMs)
    }

    fun save(text: String, title: String? = null): AssistantNoteSaveResult = synchronized(fileLock) {
        val notes = readNotes()
        if (notes.size >= maxNotes) return@synchronized AssistantNoteSaveResult.Full
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty())
        val cappedText = normalizedText.truncateAtWordBoundary(MAX_TEXT_CHARS)
        val normalizedTitle = title
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.truncateAtWordBoundary(MAX_TITLE_CHARS)
            ?: titleFrom(cappedText)
        val id = uniqueId(notes.asSequence().map(AssistantNote::id).toSet())
        val note = AssistantNote(
            id = id,
            title = normalizedTitle,
            text = cappedText,
            createdAtMs = clock(),
        )
        persist(notes + note)
        AssistantNoteSaveResult.Saved(
            note = note,
            textTruncated = cappedText.length < normalizedText.length,
        )
    }

    fun delete(id: String): Boolean = synchronized(fileLock) {
        val notes = readNotes()
        if (notes.none { it.id == id }) return@synchronized false
        persist(notes.filterNot { it.id == id })
        true
    }

    private val fileLock: Any
        get() = FILE_LOCKS.computeIfAbsent(storeFile.absoluteFile.normalize().path) { Any() }

    private fun uniqueId(existing: Set<String>): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idGenerator()
            if (NOTE_ID.matches(candidate) && candidate !in existing) return candidate
        }
        error("Could not allocate a unique note id.")
    }

    private fun readNotes(): List<AssistantNote> {
        if (!storeFile.isFile) return emptyList()
        return runCatching {
            val values = JSONObject(storeFile.readText(Charsets.UTF_8)).getJSONArray(JSON_NOTES)
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    val id = value.getString(JSON_ID)
                    if (!NOTE_ID.matches(id)) continue
                    add(
                        AssistantNote(
                            id = id,
                            title = value.getString(JSON_TITLE).take(MAX_TITLE_CHARS),
                            text = value.getString(JSON_TEXT).take(MAX_TEXT_CHARS),
                            createdAtMs = value.getLong(JSON_CREATED_AT_MS),
                        ),
                    )
                }
            }.takeLast(maxNotes)
        }.onFailure { error ->
            logger("Assistant note store read failed: ${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    private fun persist(notes: List<AssistantNote>) {
        val root = JSONObject()
            .put(JSON_VERSION, STORE_VERSION)
            .put(
                JSON_NOTES,
                JSONArray().apply {
                    notes.forEach { note ->
                        put(
                            JSONObject()
                                .put(JSON_ID, note.id)
                                .put(JSON_TITLE, note.title)
                                .put(JSON_TEXT, note.text)
                                .put(JSON_CREATED_AT_MS, note.createdAtMs),
                        )
                    }
                },
            )
        writeAssistantJsonAtomically(storeFile, root.toString(), fileOperations)
    }

    companion object {
        internal const val STORE_FILE_NAME = "assistant_notes_v1.json"
        const val MAX_NOTES = 200
        const val MAX_TEXT_CHARS = 2_000
        const val MAX_TITLE_CHARS = 80
        const val MAX_ID_ATTEMPTS = 100
        const val NOTE_ID_PREFIX = "n_"
        const val STORE_VERSION = 1
        const val TAG = "NexusAssistant"
        const val JSON_VERSION = "version"
        const val JSON_NOTES = "notes"
        const val JSON_ID = "id"
        const val JSON_TITLE = "title"
        const val JSON_TEXT = "text"
        const val JSON_CREATED_AT_MS = "createdAtMs"
        val NOTE_ID = Regex("n_[a-z0-9]{8}")
        val FILE_LOCKS = ConcurrentHashMap<String, Any>()

        fun titleFrom(text: String): String {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return firstLine.replace(Regex("\\s+"), " ")
                .trim()
                .truncateAtWordBoundary(MAX_TITLE_CHARS)
                .ifBlank { "Note" }
        }
    }
}

internal fun String.truncateAtWordBoundary(maxChars: Int): String {
    require(maxChars > 0)
    val value = trim()
    if (value.length <= maxChars) return value
    val candidate = value.take(maxChars)
    val boundary = candidate.indexOfLast(Char::isWhitespace)
    return if (boundary > 0) candidate.take(boundary).trimEnd() else candidate
}

private val assistantIdRandom = SecureRandom()
private const val ASSISTANT_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

internal fun assistantShortId(prefix: String): String = buildString(prefix.length + 8) {
    append(prefix)
    repeat(8) {
        append(ASSISTANT_ID_ALPHABET[assistantIdRandom.nextInt(ASSISTANT_ID_ALPHABET.length)])
    }
}
