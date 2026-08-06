package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkTagStreamFilterTest {
    @Test
    fun stripsWholeThinkBlockFromOneDelta() {
        val filter = ThinkTagStreamFilter()

        val emitted = filter.filter("<think>hidden reasoning</think>The answer") + filter.finish()

        assertEquals("The answer", emitted)
    }

    @Test
    fun stripsOpeningAndClosingTagsSplitAcrossDeltas() {
        val filter = ThinkTagStreamFilter()
        val emitted = listOf(
            filter.filter("Before <th"),
            filter.filter("ink>hidden</thi"),
            filter.filter("nk>\n\nAnswer"),
            filter.finish(),
        )

        assertEquals(listOf("Before ", "", "\n\nAnswer", ""), emitted)
    }

    @Test
    fun stripsThinkBlockSpanningManyDeltas() {
        val filter = ThinkTagStreamFilter()
        val emitted = listOf(
            "<think>",
            "First thought. ",
            "Second thought. ",
            "Still reasoning.",
            "</think>",
            "Visible",
        ).joinToString("") { delta -> filter.filter(delta) } + filter.finish()

        assertEquals("Visible", emitted)
    }

    @Test
    fun stripsMultipleThinkBlocks() {
        val filter = ThinkTagStreamFilter()

        val emitted = filter.filter(
            "A<think>first</think>B<think>second</think>C",
        ) + filter.finish()

        assertEquals("ABC", emitted)
    }

    @Test
    fun suppressesEntireUnclosedThinkBlock() {
        val filter = ThinkTagStreamFilter()

        val emitted = filter.filter("<think>unfinished reasoning") + filter.finish()

        assertEquals("", emitted)
    }

    @Test
    fun preservesLoneLessThanAndNonMatchingTags() {
        val filter = ThinkTagStreamFilter()
        val input = "Use 1 < 2 and <thought>this</thought> <"

        val emitted = filter.filter(input) + filter.finish()

        assertEquals(input, emitted)
    }

    @Test
    fun preservesLeadingNewlinesAfterClosingTag() {
        val filter = ThinkTagStreamFilter()

        val emitted = filter.filter("<think>hidden</think>\n\nAnswer") + filter.finish()

        assertEquals("\n\nAnswer", emitted)
    }
}
