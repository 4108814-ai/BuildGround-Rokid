package com.anezium.rokidbus.plugin.relay

internal enum class RelayReaderSegmentKind {
    HEADER,
    PROSE,
    ASIDE,
}

/** Android-free reader payload, adapted to NexusReader at the service boundary. */
internal data class RelayReaderSegment(
    val kind: RelayReaderSegmentKind,
    val text: String,
    val emphasis: Boolean = false,
)

internal data class RelayReaderDocument(
    val title: String,
    val footer: String,
    val contentKey: String,
    val handlesBack: Boolean,
    val segments: List<RelayReaderSegment>,
) {
    companion object {
        const val MAX_SEGMENTS = 240
        const val MAX_SEGMENT_CHARS = 4_096
        const val MAX_DOCUMENT_CHARS = 20_000

        private const val MAX_TITLE_CHARS = 120
        private const val MAX_FOOTER_CHARS = 240
        private const val MAX_FOOTER_SOURCE_CHARS = 80
        private const val EMPTY_THREAD_TEXT = "No message text"
        private const val THREAD_CONTENT_PREFIX = "relay-thread-"

        fun from(
            snapshot: RelayInboxSnapshot,
            threadStatus: String?,
            canReply: Boolean,
        ): RelayReaderDocument {
            val title = snapshot.sender
                .takeUnless(String::isBlank)
                ?.trim()
                .orEmpty()
                .ifBlank { snapshot.appLabel.trim() }
                .ifBlank { "Relay" }
                .take(MAX_TITLE_CHARS)
            val appLabel = snapshot.appLabel.trim()
            val messages = RelayInboxCatalog.threadMessages(snapshot.renderedText)
            val soloVoice = messages.all { message ->
                message.speaker.isBlank() || message.speaker.equals(title, ignoreCase = true)
            }

            val keptMessages = keepNewestWithinBudget(
                messages = messages,
                soloVoice = soloVoice,
                title = title,
                appLabel = appLabel,
                threadStatus = threadStatus,
            )
            val segments = readerSegments(
                messages = keptMessages,
                soloVoice = soloVoice,
                title = title,
                appLabel = appLabel,
                threadStatus = threadStatus,
            )

            val instruction = if (canReply) {
                "tap to reply · back to inbox"
            } else {
                "read only · back to inbox"
            }
            val footer = listOf(appLabel.take(MAX_FOOTER_SOURCE_CHARS), instruction)
                .filter(String::isNotBlank)
                .joinToString(" · ")
                .take(MAX_FOOTER_CHARS)
            return RelayReaderDocument(
                title = title,
                footer = footer,
                contentKey = "$THREAD_CONTENT_PREFIX${snapshot.id}",
                handlesBack = true,
                segments = segments,
            )
        }

        /**
         * Drops whole oldest messages until the document fits the caps.
         *
         * This used to be "drop one, rebuild everything, re-measure" — quadratic
         * in messages, and a notification that parses into thousands of tiny
         * thread messages turned opening the thread into millions of throwaway
         * allocations on the main thread. Each message's cost is measured once
         * here; dropping the front only has to account for the one header that
         * can change hands: when the dropped message opened a speaker run, the
         * next message of that run becomes the run's first and inherits its
         * header.
         */
        private fun keepNewestWithinBudget(
            messages: List<RelayInboxCatalog.RelayThreadMessage>,
            soloVoice: Boolean,
            title: String,
            appLabel: String,
            threadStatus: String?,
        ): List<RelayInboxCatalog.RelayThreadMessage> {
            if (messages.isEmpty()) return messages

            val soloHeaderChars = if (soloVoice) {
                listOf(title, appLabel.takeIf(String::isNotBlank))
                    .filterNotNull()
                    .joinToString(" · ")
                    .take(MAX_SEGMENT_CHARS)
                    .length
            } else {
                0
            }
            val overheadSegments = (if (soloVoice) 1 else 0) + (if (threadStatus != null) 1 else 0)
            val overheadChars = soloHeaderChars +
                (threadStatus?.take(MAX_SEGMENT_CHARS)?.length ?: 0)

            val proseSegments = IntArray(messages.size)
            val proseChars = IntArray(messages.size)
            val headerSegments = IntArray(messages.size)
            val headerChars = IntArray(messages.size)
            var totalSegments = overheadSegments
            var totalChars = overheadChars
            var previousSpeaker: String? = null
            messages.forEachIndexed { index, message ->
                val prose = splitProse(message.text)
                proseSegments[index] = prose.size
                proseChars[index] = prose.sumOf { it.length }
                if (!soloVoice && !message.speaker.equals(previousSpeaker, ignoreCase = true)) {
                    headerSegments[index] = 1
                    headerChars[index] = message.speaker.take(MAX_SEGMENT_CHARS).length
                }
                previousSpeaker = message.speaker
                totalSegments += proseSegments[index] + headerSegments[index]
                totalChars += proseChars[index] + headerChars[index]
            }

            var first = 0
            while (
                first < messages.size &&
                (totalSegments > MAX_SEGMENTS || totalChars > MAX_DOCUMENT_CHARS)
            ) {
                totalSegments -= proseSegments[first] + headerSegments[first]
                totalChars -= proseChars[first] + headerChars[first]
                val next = first + 1
                if (
                    !soloVoice &&
                    next < messages.size &&
                    headerSegments[first] == 1 &&
                    headerSegments[next] == 0
                ) {
                    // The dropped message opened this speaker run; its successor
                    // now fronts the run and pays for the header instead.
                    headerSegments[next] = 1
                    headerChars[next] = messages[next].speaker.take(MAX_SEGMENT_CHARS).length
                    totalSegments += 1
                    totalChars += headerChars[next]
                }
                first = next
            }
            return if (first == 0) messages else messages.drop(first)
        }

        private fun readerSegments(
            messages: List<RelayInboxCatalog.RelayThreadMessage>,
            soloVoice: Boolean,
            title: String,
            appLabel: String,
            threadStatus: String?,
        ): List<RelayReaderSegment> = buildList {
            if (soloVoice) {
                val header = listOf(title, appLabel.takeIf(String::isNotBlank))
                    .filterNotNull()
                    .joinToString(" · ")
                    .take(MAX_SEGMENT_CHARS)
                add(
                    RelayReaderSegment(
                        kind = RelayReaderSegmentKind.HEADER,
                        text = header,
                        emphasis = title.equals("You", ignoreCase = true),
                    ),
                )
            }

            val visibleMessages = messages.ifEmpty {
                listOf(RelayInboxCatalog.RelayThreadMessage(speaker = "", text = EMPTY_THREAD_TEXT))
            }
            var previousSpeaker: String? = null
            for (message in visibleMessages) {
                if (!soloVoice && !message.speaker.equals(previousSpeaker, ignoreCase = true)) {
                    add(
                        RelayReaderSegment(
                            kind = RelayReaderSegmentKind.HEADER,
                            text = message.speaker.take(MAX_SEGMENT_CHARS),
                            emphasis = message.speaker.equals("You", ignoreCase = true),
                        ),
                    )
                }
                previousSpeaker = message.speaker
                splitProse(message.text).forEach { prose ->
                    add(RelayReaderSegment(kind = RelayReaderSegmentKind.PROSE, text = prose))
                }
            }
            threadStatus?.let { status ->
                add(
                    RelayReaderSegment(
                        kind = RelayReaderSegmentKind.ASIDE,
                        text = status.take(MAX_SEGMENT_CHARS),
                    ),
                )
            }
        }

        private fun splitProse(value: String): List<String> = buildList {
            var remaining = value
            while (remaining.length > MAX_SEGMENT_CHARS) {
                val contractEnd = safeUtf16End(remaining, MAX_SEGMENT_CHARS)
                val wordBoundary = remaining.lastIndexOf(' ', startIndex = contractEnd)
                    .takeIf { it > 0 }
                val end = wordBoundary ?: contractEnd
                add(remaining.substring(0, end))
                remaining = remaining.substring(if (wordBoundary == null) end else end + 1)
            }
            if (remaining.isNotEmpty()) add(remaining)
        }

        private fun safeUtf16End(value: String, requestedEnd: Int): Int {
            var end = requestedEnd.coerceIn(0, value.length)
            if (
                end in 1 until value.length &&
                Character.isHighSurrogate(value[end - 1]) &&
                Character.isLowSurrogate(value[end])
            ) {
                end -= 1
            }
            return end
        }
    }
}
