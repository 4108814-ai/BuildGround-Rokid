package com.anezium.rokidbus.plugin.assistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AssistantMeetingRecorder(
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private var startedAt: ZonedDateTime? = null
    private val segments = mutableListOf<String>()

    val active: Boolean
        get() = startedAt != null

    val segmentCount: Int
        get() = segments.size

    fun start(): Boolean {
        if (active) return false
        startedAt = now()
        segments.clear()
        return true
    }

    fun append(text: String): Boolean {
        if (!active) return false
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return false
        segments += normalized.take(MAX_SEGMENT_CHARS)
        if (segments.size > MAX_SEGMENTS) {
            segments.removeAt(0)
        }
        return true
    }

    fun finish(): AssistantMeetingTranscript? {
        val started = startedAt ?: return null
        val finished = now()
        val snapshot = AssistantMeetingTranscript(
            startedAt = started,
            finishedAt = finished,
            segments = segments.toList(),
        )
        startedAt = null
        segments.clear()
        return snapshot
    }

    fun cancel() {
        startedAt = null
        segments.clear()
    }

    companion object {
        private const val MAX_SEGMENTS = 500
        private const val MAX_SEGMENT_CHARS = 2000
    }
}

internal data class AssistantMeetingTranscript(
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
