package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.ReleaseNotesMarkdown.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {

    @Test
    fun `hard-wrapped bullet lines join into one item`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            - **A second message no longer arrives on a dark display.** When a short
              notification woke the glasses and expired, the display went back to sleep.
            - Another change.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Block.Bullet(
                    "**A second message no longer arrives on a dark display.** When a short " +
                        "notification woke the glasses and expired, the display went back to sleep.",
                ),
                Block.Bullet("Another change."),
            ),
            blocks,
        )
    }

    @Test
    fun `artifact section is stripped for display`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            - Android 11 support: the plugin now installs on Android 11 phones.

            ### Artifact

            - File: `feeds-phone-release.apk`
            - SHA-256: `2e64a18a`
            """.trimIndent(),
        )

        assertEquals(listOf(Block.Bullet("Android 11 support: the plugin now installs on Android 11 phones.")), blocks)
    }

    @Test
    fun `artifact stripping ends at the next unrelated heading`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            ### Artifacts

            - File: `a.apk`

            ### Notes

            Real content.
            """.trimIndent(),
        )

        assertEquals(listOf(Block.Heading("Notes"), Block.Paragraph("Real content.")), blocks)
    }

    @Test
    fun `paragraphs split on blank lines and keep headings`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            ## Highlights

            First paragraph
            continues here.

            Second paragraph.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Block.Heading("Highlights"),
                Block.Paragraph("First paragraph continues here."),
                Block.Paragraph("Second paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun `blank or whitespace notes parse to nothing`() {
        assertTrue(ReleaseNotesMarkdown.parse("").isEmpty())
        assertTrue(ReleaseNotesMarkdown.parse("\n  \n").isEmpty())
    }
}
