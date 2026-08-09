package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReaderDocumentTest {
    @Test
    fun `solo thread has one conversation header and one prose segment per message`() {
        val document = document(
            sender = "Alice",
            appLabel = "Signal",
            text = "Alice: First message\nAlice: Second message",
        )

        assertEquals("Alice", document.title)
        assertEquals("Signal · tap to reply · back to inbox", document.footer)
        assertEquals("relay-thread-thread-1", document.contentKey)
        assertTrue(document.handlesBack)
        assertEquals(
            listOf(
                segment(RelayReaderSegmentKind.HEADER, "Alice · Signal"),
                segment(RelayReaderSegmentKind.PROSE, "First message"),
                segment(RelayReaderSegmentKind.PROSE, "Second message"),
            ),
            document.segments,
        )
    }

    @Test
    fun `group thread emits a header only when the speaker changes`() {
        val document = document(
            sender = "Friends",
            text = "Alice: One\nAlice: Two\nBob: Three\nBob: Four\nAlice: Five",
        )

        assertEquals(
            listOf(
                segment(RelayReaderSegmentKind.HEADER, "Alice"),
                segment(RelayReaderSegmentKind.PROSE, "One"),
                segment(RelayReaderSegmentKind.PROSE, "Two"),
                segment(RelayReaderSegmentKind.HEADER, "Bob"),
                segment(RelayReaderSegmentKind.PROSE, "Three"),
                segment(RelayReaderSegmentKind.PROSE, "Four"),
                segment(RelayReaderSegmentKind.HEADER, "Alice"),
                segment(RelayReaderSegmentKind.PROSE, "Five"),
            ),
            document.segments,
        )
    }

    @Test
    fun `wearer headers are emphasized case insensitively`() {
        val document = document(
            sender = "Friends",
            text = "Alice: Question\nyOu: Answer\nBob: Follow-up",
        )

        val headers = document.segments.filter { it.kind == RelayReaderSegmentKind.HEADER }
        assertFalse(headers[0].emphasis)
        assertTrue(headers[1].emphasis)
        assertFalse(headers[2].emphasis)
    }

    @Test
    fun `long messages split into consecutive prose segments at word boundaries`() {
        val message = "word ".repeat(1_100).trim()

        val prose = document(sender = "Alice", text = "Alice: $message")
            .segments
            .filter { it.kind == RelayReaderSegmentKind.PROSE }

        assertTrue(prose.size > 1)
        assertTrue(prose.all { it.text.length <= RelayReaderDocument.MAX_SEGMENT_CHARS })
        assertTrue(prose.all { it.text == it.text.trim() })
        assertEquals(message, prose.joinToString(" ") { it.text })
    }

    @Test
    fun `documents over twenty thousand characters drop whole oldest messages`() {
        val old = "old-" + "o".repeat(7_996)
        val middle = "middle-" + "m".repeat(7_993)
        val newest = "newest-" + "n".repeat(7_993)

        val document = document(
            sender = "Alice",
            text = "Alice: $old\nAlice: $middle\nAlice: $newest",
        )
        val prose = document.segments.filter { it.kind == RelayReaderSegmentKind.PROSE }

        assertFalse(prose.any { it.text.contains("old-") || it.text.all { char -> char == 'o' } })
        assertTrue(prose.any { it.text.startsWith("middle-") })
        assertTrue(prose.any { it.text.startsWith("newest-") })
        assertTrue(document.segments.sumOf { it.text.length } <= RelayReaderDocument.MAX_DOCUMENT_CHARS)
        assertTrue(document.segments.size <= RelayReaderDocument.MAX_SEGMENTS)
    }

    @Test
    fun `a spaceless message hard-splits at the segment cap`() {
        val message = "x".repeat(RelayReaderDocument.MAX_SEGMENT_CHARS + 1)

        val prose = document(sender = "Alice", text = "Alice: $message")
            .segments
            .filter { it.kind == RelayReaderSegmentKind.PROSE }

        assertEquals(2, prose.size)
        assertEquals(RelayReaderDocument.MAX_SEGMENT_CHARS, prose[0].text.length)
        assertEquals(1, prose[1].text.length)
        assertEquals(message, prose.joinToString("") { it.text })
    }

    @Test
    fun `an emoji straddling the segment cap is never torn apart`() {
        // One astral emoji (two UTF-16 units) positioned so the cap lands
        // between its surrogates; a torn pair renders as garbage on the HUD.
        val message = "x".repeat(RelayReaderDocument.MAX_SEGMENT_CHARS - 1) + "😀" + "y".repeat(10)

        val prose = document(sender = "Alice", text = "Alice: $message")
            .segments
            .filter { it.kind == RelayReaderSegmentKind.PROSE }

        assertTrue(prose.all { it.text.length <= RelayReaderDocument.MAX_SEGMENT_CHARS })
        prose.forEach { segment ->
            assertFalse(Character.isHighSurrogate(segment.text.last()))
            assertFalse(Character.isLowSurrogate(segment.text.first()))
        }
        assertEquals(message, prose.joinToString("") { it.text })
    }

    @Test
    fun `a thread of many tiny messages is capped by segment count`() {
        // Far more messages than MAX_SEGMENTS; each parses into one prose
        // segment, so the newest window must fit exactly under the cap.
        val lines = (1..2_000).joinToString("\n") { index -> "Alice: m$index" }

        val document = document(sender = "Alice", text = lines)

        assertTrue(document.segments.size <= RelayReaderDocument.MAX_SEGMENTS)
        val prose = document.segments.filter { it.kind == RelayReaderSegmentKind.PROSE }
        assertEquals("m2000", prose.last().text)
        assertEquals("m${2_000 - prose.size + 1}", prose.first().text)
    }

    @Test
    fun `group headers survive dropping the front of a speaker run`() {
        // Enough of Alice's messages that the budget drops some of hers: the
        // kept remainder of her run must still open with her header.
        val aliceLines = (1..300).joinToString("\n") { index -> "Alice: a$index" }
        val document = document(sender = "Friends", text = "$aliceLines\nBob: reply")

        assertTrue(document.segments.size <= RelayReaderDocument.MAX_SEGMENTS)
        assertEquals(RelayReaderSegmentKind.HEADER, document.segments.first().kind)
        assertEquals("Alice", document.segments.first().text)
        val bobHeader = document.segments.indexOfFirst {
            it.kind == RelayReaderSegmentKind.HEADER && it.text == "Bob"
        }
        assertTrue(bobHeader > 0)
        assertEquals(
            segment(RelayReaderSegmentKind.PROSE, "reply"),
            document.segments[bobHeader + 1],
        )
    }

    @Test
    fun `thread status is the final aside`() {
        val document = document(threadStatus = "Reply is no longer available.")

        assertEquals(
            segment(RelayReaderSegmentKind.ASIDE, "Reply is no longer available."),
            document.segments.last(),
        )
    }

    @Test
    fun `empty thread gets readable fallback prose`() {
        val document = document(sender = "", appLabel = "Messages", text = "")

        assertEquals("Messages", document.title)
        assertEquals(
            listOf(
                segment(RelayReaderSegmentKind.HEADER, "Messages · Messages"),
                segment(RelayReaderSegmentKind.PROSE, "No message text"),
            ),
            document.segments,
        )
    }

    private fun document(
        sender: String = "Alice",
        appLabel: String = "Signal",
        text: String = "Alice: Hello",
        threadStatus: String? = null,
    ): RelayReaderDocument = RelayReaderDocument.from(
        snapshot = RelayInboxSnapshot(
            id = "thread-1",
            sender = sender,
            appLabel = appLabel,
            renderedText = text,
            capturedAtMs = 0L,
        ),
        threadStatus = threadStatus,
        canReply = true,
    )

    private fun segment(
        kind: RelayReaderSegmentKind,
        text: String,
        emphasis: Boolean = false,
    ) = RelayReaderSegment(kind = kind, text = text, emphasis = emphasis)
}
