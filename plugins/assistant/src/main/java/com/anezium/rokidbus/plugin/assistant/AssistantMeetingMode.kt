package com.anezium.rokidbus.plugin.assistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AssistantMeetingRecorder(
    private val persistence: AssistantMeetingPersistence = NoopAssistantMeetingPersistence,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private var meetingId: String? = null
    private var startedAt: ZonedDateTime? = null
    private val segments = mutableListOf<String>()

    init {
        persistence.loadActive()?.let { draft ->
            meetingId = draft.id
            startedAt = draft.startedAt
            segments += draft.segments.takeLast(MAX_SEGMENTS)
        }
    }

    val active: Boolean
        get() = startedAt != null

    val segmentCount: Int
        get() = segments.size

    fun start(): Boolean {
        if (active) return false
        val started = now()
        meetingId = persistence.start(started)
        startedAt = started
        segments.clear()
        return true
    }

    fun append(text: String): Boolean {
        if (!active) return false
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return false
        val capped = normalized.take(MAX_SEGMENT_CHARS)
        segments += capped
        if (segments.size > MAX_SEGMENTS) {
            segments.removeAt(0)
        }
        persistence.append(meetingId, capped)
        return true
    }

    fun finish(): AssistantMeetingTranscript? {
        val started = startedAt ?: return null
        val finished = now()
        val snapshot = AssistantMeetingTranscript(
            id = meetingId,
            startedAt = started,
            finishedAt = finished,
            segments = segments.toList(),
        )
        val archivedId = persistence.complete(snapshot)
        meetingId = null
        startedAt = null
        segments.clear()
        return if (archivedId == snapshot.id) snapshot else snapshot.copy(id = archivedId ?: snapshot.id)
    }

    fun cancel() {
        persistence.cancel(meetingId)
        meetingId = null
        startedAt = null
        segments.clear()
    }

    companion object {
        private const val MAX_SEGMENTS = 500
        private const val MAX_SEGMENT_CHARS = 2000
    }
}

internal data class AssistantMeetingTranscript(
    val id: String? = null,
    val startedAt: ZonedDateTime,
    val finishedAt: ZonedDateTime,
    val segments: List<String>,
) {
    fun summaryPrompt(): String = buildString {
        append(
            "Составь краткий деловой протокол совещания на русском языке по расшифровке ниже. " +
                "Ничего не придумывай. Если участник, ответственный, срок или тема явно не звучали, " +
                "так и укажи или пропусти поле. Структура: тема и время; участники только если " +
                "они названы; зафиксированные факты; принятые решения; поручения с задачей, " +
                "ответственным и сроком; открытые вопросы; риски. Сохраняй числа, суммы, даты и " +
                "названия дословно по смыслу.\n\n",
        )
        append("Начало: ")
        append(startedAt.format(MEETING_TIME_FORMAT))
        append("\nОкончание: ")
        append(finishedAt.format(MEETING_TIME_FORMAT))
        append("\n\nРасшифровка:\n")
        if (segments.isEmpty()) {
            append("[Нет записанных фрагментов]")
        } else {
            segments.forEachIndexed { index, segment ->
                append(index + 1)
                append(". ")
                append(segment)
                append('\n')
            }
        }
    }

    companion object {
        private val MEETING_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm xxx",
            Locale.ENGLISH,
        )
    }
}

internal fun isMeetingStartCommand(text: String): Boolean {
    val normalized = normalizeMeetingCommand(text)
    return normalized in setOf(
        "начать совещание",
        "начни совещание",
        "начать встречу",
        "начни встречу",
        "режим совещания",
        "start meeting",
        "start the meeting",
        "meeting mode",
    )
}

internal fun isMeetingStopCommand(text: String): Boolean {
    val normalized = normalizeMeetingCommand(text)
    return normalized in setOf(
        "закончить совещание",
        "закончи совещание",
        "завершить совещание",
        "заверши совещание",
        "закончить встречу",
        "закончи встречу",
        "завершить встречу",
        "заверши встречу",
        "стоп совещание",
        "stop meeting",
        "end meeting",
        "finish meeting",
    )
}

private fun normalizeMeetingCommand(value: String): String =
    value
        .lowercase(Locale.getDefault())
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
