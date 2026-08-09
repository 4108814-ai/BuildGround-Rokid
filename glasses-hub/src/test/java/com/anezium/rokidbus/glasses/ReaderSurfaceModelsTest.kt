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
