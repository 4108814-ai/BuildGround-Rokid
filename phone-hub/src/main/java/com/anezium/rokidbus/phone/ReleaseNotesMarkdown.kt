package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The subset of Markdown that release notes actually use: bullets, bold,
 * inline code, and `###` headings. Registry notes end with a provenance
 * "### Artifact" section that is metadata, not changelog — stripped here.
 */
internal object ReleaseNotesMarkdown {

    sealed interface Block {
        data class Heading(val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Paragraph(val text: String) : Block
    }

    fun parse(markdown: String, stripArtifactSection: Boolean = true): List<Block> {
        val blocks = mutableListOf<Block>()
        var current: StringBuilder? = null
        var currentIsBullet = false
        var skippingArtifact = false

        fun flush() {
            val text = current?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                blocks += if (currentIsBullet) Block.Bullet(text) else Block.Paragraph(text)
            }
            current = null
            currentIsBullet = false
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val headingMatch = HEADING.matchEntire(trimmed)
            when {
                headingMatch != null -> {
                    flush()
                    val title = headingMatch.groupValues[2].trim()
                    skippingArtifact = stripArtifactSection && title.lowercase().startsWith("artifact")
                    if (!skippingArtifact && title.isNotEmpty()) blocks += Block.Heading(title)
                }
                skippingArtifact -> Unit
                trimmed.isEmpty() -> flush()
                BULLET.containsMatchIn(trimmed) -> {
                    flush()
                    currentIsBullet = true
                    current = StringBuilder(trimmed.replaceFirst(BULLET, ""))
                }
                else -> {
                    // Hard-wrapped continuation of the open bullet or paragraph.
                    val open = current
                    if (open == null) {
                        current = StringBuilder(trimmed)
                        currentIsBullet = false
                    } else {
                        open.append(' ').append(trimmed)
                    }
                }
            }
        }
        flush()
        return blocks
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^[-*]\\s+")
}

/** Renders parsed release notes with the app's ink hierarchy. */
internal object ReleaseNotesRenderer {

    fun render(context: Context, markdown: String): CharSequence {
        val blocks = ReleaseNotesMarkdown.parse(markdown)
        val out = SpannableStringBuilder()
        val indent = NexusUi.dp(context, 13)
        blocks.forEachIndexed { index, block ->
            if (index > 0) out.append("\n\n")
            when (block) {
                is ReleaseNotesMarkdown.Block.Heading -> {
                    val start = out.length
                    out.append(block.text.uppercase())
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.78f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(NexusUi.INK3), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is ReleaseNotesMarkdown.Block.Bullet -> {
                    val start = out.length
                    out.append("•  ")
                    val bulletEnd = out.length
                    appendInline(out, block.text)
                    out.setSpan(ForegroundColorSpan(NexusUi.GREEN_DIM), start, bulletEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(
                        LeadingMarginSpan.Standard(0, indent),
                        start,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is ReleaseNotesMarkdown.Block.Paragraph -> appendInline(out, block.text)
            }
        }
        return out
    }

    /** `**bold**` brightens to primary ink; `` `code` `` switches to mono. */
    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            out.append(text, cursor, match.range.first)
            val start = out.length
            val bold = match.groupValues[1]
            if (bold.isNotEmpty()) {
                out.append(bold)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(NexusUi.INK), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(match.groupValues[2])
                out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.9f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cursor = match.range.last + 1
        }
        out.append(text, cursor, text.length)
    }

    private val INLINE = Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`")
}
