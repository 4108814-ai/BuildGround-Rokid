package com.anezium.rokidbus.glasses

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderSurfaceModelsTest {
    @Test
    fun `reader parser skips unknown kinds and keeps blank paragraph breaks`() {
        val surface = NexusSurface.fromPayload(
            readerPayload(
                JSONArray()
                    .put(JSONObject().put("kind", "header").put("text", "").put("emphasis", true))
                    .put(JSONObject().put("kind", "future").put("text", "ignored"))
                    .put(JSONObject().put("kind", "prose").put("text", ""))
                    .put(JSONObject().put("kind", "aside").put("text", "")),
            ).apply {
                put("lines", JSONArray().put("must not become a card row"))
                put("anchor", JSONObject().put("positionMs", 10))
                put("mediaTitle", "must not become media")
                put("artwork", JSONObject().put("encoding", "mono1"))
            },
        )

        assertTrue(surface.isReader)
        assertEquals(
            listOf(ReaderSegmentKind.HEADER, ReaderSegmentKind.PROSE, ReaderSegmentKind.ASIDE),
            surface.readerSegments.map { it.kind },
        )
        assertTrue(surface.readerSegments[0].emphasis)
        assertEquals("", surface.readerSegments[1].text)
        assertTrue(surface.rows.isEmpty())
        assertTrue(surface.timedLines.isEmpty())
        assertNull(surface.anchor)
        assertFalse(surface.isMedia)
        assertFalse(surface.isImage)
        assertNull(surface.artwork)
    }

    @Test
    fun `reader parser truncates every SDK cap without throwing`() {
        val segments = JSONArray()
        repeat(241) { index ->
            segments.put(
                JSONObject()
                    .put("kind", "prose")
                    .put("text", if (index < 10) "x".repeat(5_000) else "tail"),
            )
        }

        val surface = NexusSurface.fromPayload(
            readerPayload(segments).apply {
                put("title", "t".repeat(121))
                put("subtitle", "s".repeat(241))
                put("footer", "f".repeat(241))
                put("contentKey", "k".repeat(129))
            },
        )

        assertEquals(120, surface.title.length)
        assertEquals(240, surface.subtitle.length)
        assertEquals(240, surface.footer.length)
        assertEquals(128, surface.contentKey.length)
        assertEquals(240, surface.readerSegments.size)
        assertTrue(surface.readerSegments.all { it.text.length <= 4_096 })
        assertEquals(40_000, surface.readerSegments.sumOf { it.text.length })
    }

    @Test
    fun `reader parser accepts top and defaults invalid anchors to bottom`() {
        val segments = JSONArray().put(JSONObject().put("kind", "prose").put("text", "Body"))

        assertEquals(
            ReaderAnchor.TOP,
            NexusSurface.fromPayload(readerPayload(segments).put("readerAnchor", "top")).readerAnchor,
        )
        assertEquals(
            ReaderAnchor.BOTTOM,
            NexusSurface.fromPayload(readerPayload(segments)).readerAnchor,
        )
        assertEquals(
            ReaderAnchor.BOTTOM,
            NexusSurface.fromPayload(readerPayload(segments).put("readerAnchor", "future")).readerAnchor,
        )
        assertEquals(
            ReaderAnchor.BOTTOM,
            NexusSurface.fromPayload(readerPayload(segments).put("readerAnchor", "")).readerAnchor,
        )
    }

    @Test
    fun `reader parser preserves anchor when merging an update without the key`() {
        val segments = JSONArray().put(JSONObject().put("kind", "prose").put("text", "Body"))
        val previous = NexusSurface.fromPayload(readerPayload(segments).put("readerAnchor", "top"))
        val update = JSONObject()
            .put("surfaceId", "agents:thread")
            .put("seq", 43)
            .put("kind", "reader")

        val merged = NexusSurface.fromPayload(update, previous)

        assertEquals(ReaderAnchor.TOP, merged.readerAnchor)
    }

    private fun readerPayload(segments: JSONArray): JSONObject = JSONObject()
        .put("surfaceId", "agents:thread")
        .put("seq", 42)
        .put("kind", "reader")
        .put("title", "Conversation")
        .put("subtitle", "2 turns")
        .put("footer", "Back")
        .put("contentKey", "conversation-42")
        .put("segments", segments)
}

class ReaderScrollTargetTest {
    @Test
    fun `first render bottom opens at maximum scroll`() {
        assertEquals(
            900,
            resolveReaderScrollTarget(false, false, 0, 900, ReaderAnchor.BOTTOM),
        )
    }

    @Test
    fun `first render top opens at zero`() {
        assertEquals(
            0,
            resolveReaderScrollTarget(false, false, 700, 900, ReaderAnchor.TOP),
        )
    }

    @Test
    fun `top update near bottom restores previous offset`() {
        assertEquals(
            850,
            resolveReaderScrollTarget(true, true, 850, 900, ReaderAnchor.TOP),
        )
    }

    @Test
    fun `bottom update near bottom follows maximum scroll`() {
        assertEquals(
            900,
            resolveReaderScrollTarget(true, true, 850, 900, ReaderAnchor.BOTTOM),
        )
    }

    @Test
    fun `bottom update mid document restores clamped previous offset`() {
        assertEquals(
            400,
            resolveReaderScrollTarget(true, false, 400, 900, ReaderAnchor.BOTTOM),
        )
        assertEquals(
            900,
            resolveReaderScrollTarget(true, false, 1_000, 900, ReaderAnchor.BOTTOM),
        )
    }

    @Test
    fun `zero maximum scroll resolves to zero for both anchors`() {
        ReaderAnchor.entries.forEach { anchor ->
            assertEquals(
                0,
                resolveReaderScrollTarget(true, true, 100, 0, anchor),
            )
        }
    }
}
