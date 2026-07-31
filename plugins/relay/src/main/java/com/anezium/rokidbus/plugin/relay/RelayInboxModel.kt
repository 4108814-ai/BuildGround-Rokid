package com.anezium.rokidbus.plugin.relay

internal enum class RelayReplyAvailability {
    REPLIABLE,
    READ_ONLY,
}

/** Text-only notification state. Live Android reply objects deliberately stay in ReplyRepository. */
internal data class RelayInboxSnapshot(
    val id: String,
    val sender: String,
    val appLabel: String,
    val renderedText: String,
    val capturedAtMs: Long,
)

internal data class RelayInboxEntry(
    val snapshot: RelayInboxSnapshot,
    val availability: RelayReplyAvailability,
) {
    val id: String
        get() = snapshot.id
}

/** Pure counterpart of NexusCardLine, kept Android-free for local JVM tests. */
internal object RelayInboxCatalog {
    // NexusCard accepts at most 64 rows. This is a data cap, not a viewport calculation.
    const val MAX_ENTRIES = 64
    const val MAX_CARD_LINES = 64
    const val MAX_CARD_LINE_CHARS = 240
    const val MAX_BADGE_CHARS = 24

    /**
     * How wide a list row may read, in monospace columns. Measured on the
     * optics at card body size, where 38 wrapped onto a second line and broke
     * the one-row-per-conversation rhythm the list depends on.
     *
     * Not a layout decision — the renderer still owns type, width and wrapping.
     * This is the width past which a row stops being scannable.
     */
    const val LIST_LINE_CHARS = 26

    fun entries(
        snapshots: Collection<RelayInboxSnapshot>,
        liveReplyIds: Set<String>,
        limit: Int = MAX_ENTRIES,
    ): List<RelayInboxEntry> {
        val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
        return snapshots
            .sortedWith(
                compareByDescending<RelayInboxSnapshot> { it.capturedAtMs }
                    .thenBy { it.id },
            )
            .distinctBy(RelayInboxSnapshot::id)
            .take(boundedLimit)
            .map { snapshot ->
                RelayInboxEntry(
                    snapshot = snapshot,
                    availability = if (snapshot.id in liveReplyIds) {
                        RelayReplyAvailability.REPLIABLE
                    } else {
                        RelayReplyAvailability.READ_ONLY
                    },
                )
            }
    }

    /**
     * One conversation, one plain line: who it is from, then what they last said.
     *
     * Deliberately *not* a structured row. The renderer turns those into the
     * departure board they were built for — a solid phosphor chip, a marquee and
     * a column of times — which reads as a bus timetable when the screen is
     * showing a list of people. A plain card is left-aligned monospace, which is
     * what a list of conversations wants to be.
     *
     * A caret marks the selection. It costs two columns, needs no colour, no
     * inversion and no second view, and it stays legible on green optics where
     * a highlight floods the whole row.
     */
    fun lineFor(
        entry: RelayInboxEntry,
        selected: Boolean,
        width: Int = LIST_LINE_CHARS,
    ): String {
        val snapshot = entry.snapshot
        val sender = compact(snapshot.sender)
            .ifBlank { compact(snapshot.appLabel) }
            .ifBlank { "Unknown" }
        val prefix = if (selected) "> " else "  "
        // The name, and only the name. Twenty-six columns cannot hold a name and
        // a preview without ellipsizing both into uselessness — the first draw
        // read "> Relay tes... Mika: Reply from the...", which tells the wearer
        // nothing twice. The name is what they are choosing between; the thread
        // is one tap away and holds every word.
        val unreachable = entry.availability != RelayReplyAvailability.REPLIABLE
        val mark = if (unreachable) " ·" else ""
        return prefix + fitWithEllipsis(sender, width - prefix.length - mark.length) + mark
    }

    /**
     * Fits source message boundaries into the card wire field limit. This does not choose visual
     * pages or line wrapping; those remain renderer-owned.
     */
    fun cardLines(value: String): List<String> {
        val source = value
            .lineSequence()
            .map(::compact)
            .filter(String::isNotBlank)
            .toList()
            .ifEmpty { listOf("No message text") }
        return source
            .flatMap(::splitForCardContract)
            .takeLast(MAX_CARD_LINES)
    }

    private fun splitForCardContract(value: String): List<String> = buildList {
        var remaining = value
        while (remaining.length > MAX_CARD_LINE_CHARS) {
            val end = safeUtf16End(remaining, MAX_CARD_LINE_CHARS)
            add(remaining.substring(0, end))
            remaining = remaining.substring(end)
        }
        if (remaining.isNotEmpty()) add(remaining)
    }

    private fun fitWithEllipsis(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 3) return value.substring(0, safeUtf16End(value, maxChars))
        val end = safeUtf16End(value, maxChars - 3)
        return value.substring(0, end) + "..."
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

    private fun compact(value: String): String = value.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}

internal enum class RelayInboxView {
    LIST,
    THREAD,
}

internal enum class RelayInboxBackResult {
    SHOW_LIST,
    CLOSE_SURFACE,
}

/** Selection/navigation only; speech and Android reply objects stay outside this pure state. */
internal class RelayInboxSelection {
    private var itemIds: List<String> = emptyList()

    var selectedIndex: Int = 0
        private set

    var openedThreadId: String? = null
        private set

    val view: RelayInboxView
        get() = if (openedThreadId == null) RelayInboxView.LIST else RelayInboxView.THREAD

    val selectedId: String?
        get() = itemIds.getOrNull(selectedIndex)

    fun reset(ids: List<String>) {
        itemIds = ids.distinct()
        selectedIndex = 0
        openedThreadId = null
    }

    fun replaceItems(ids: List<String>) {
        val previousSelection = selectedId
        itemIds = ids.distinct()
        selectedIndex = previousSelection
            ?.let(itemIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: selectedIndex.coerceIn(0, (itemIds.size - 1).coerceAtLeast(0))
        if (openedThreadId != null && openedThreadId !in itemIds) {
            openedThreadId = null
        }
    }

    fun move(delta: Int): Boolean {
        if (view != RelayInboxView.LIST || itemIds.isEmpty() || delta == 0) return false
        selectedIndex = Math.floorMod(selectedIndex + delta, itemIds.size)
        return true
    }

    fun openSelected(): String? {
        if (view != RelayInboxView.LIST) return openedThreadId
        return selectedId?.also { openedThreadId = it }
    }

    fun back(): RelayInboxBackResult {
        if (openedThreadId != null) {
            openedThreadId = null
            return RelayInboxBackResult.SHOW_LIST
        }
        return RelayInboxBackResult.CLOSE_SURFACE
    }
}
