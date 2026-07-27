package com.anezium.rokidbus.shared

/**
 * Custom glyphs: a plugin supplies **geometry**, the platform supplies **style**.
 *
 * A plugin that needs a mark the shared set does not have declares a path — the
 * shape, and nothing else. It does not pick the colour, the stroke width, the
 * size, or the corner it lands in. The renderer draws every glyph with the same
 * `Paint`, so a plugin cannot break the HUD's look even by trying, and the
 * design system stops being something a test checks after the fact.
 *
 * This is deliberately *not* the image channel plan 012 refuses. A path is a few
 * hundred bytes of text, it costs nothing to send once at registration, and it
 * cannot carry a photo, a logo, an animation, or a second colour.
 *
 * It also closes a gap that predates activities: the bus only ever carried
 * `iconKey`, so a plugin's own mark showed on the phone and fell back to a
 * generic grid on the glasses, which never had the plugin's APK to load it from.
 */
object GlyphContract {

    /** Enough for a detailed mark, far too little for anything else. */
    const val MAX_PATH_LENGTH = 1_024

    /** A plugin gets a handful of marks, not an icon theme. */
    const val MAX_GLYPHS_PER_PLUGIN = 8

    /** Longest accepted glyph name. */
    const val MAX_NAME_LENGTH = 24

    /** Drawn whenever a glyph is absent, malformed, or newer than this build. */
    const val FALLBACK_GLYPH = "dot"

    const val ERROR_INVALID_NAME = "INVALID_GLYPH_NAME"
    const val ERROR_INVALID_PATH = "INVALID_GLYPH_PATH"
    const val ERROR_DUPLICATE_NAME = "DUPLICATE_GLYPH_NAME"
    const val ERROR_TOO_MANY = "TOO_MANY_GLYPHS"

    /** One plugin-supplied mark, keyed by name within its owner's namespace. */
    data class CustomGlyph(val name: String, val pathData: String)

    sealed interface ParseResult {
        data class Valid(val glyphs: List<CustomGlyph>) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    /**
     * Shape check for a glyph name — **not** a membership check.
     *
     * Lowercase `a-z` and single inner hyphens, no leading or trailing hyphen.
     * Hubs should validate this and nothing more: an unrecognised-but-well-formed
     * name renders as [FALLBACK_GLYPH]. Checking membership instead would turn
     * every future glyph into a hard version gate, where a plugin built against a
     * newer SDK is refused outright by an older hub rather than degrading to a
     * dot. Removing a name is the breaking change; adding one never was.
     */
    fun isWellFormedName(name: String?): Boolean {
        val value = name?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_NAME_LENGTH) return false
        if (value.startsWith('-') || value.endsWith('-') || value.contains("--")) return false
        return value.all { it in 'a'..'z' || it == '-' }
    }

    /**
     * Accept only what an SVG path is made of, and require it to start with a
     * move command.
     *
     * The renderer hands this to a path parser, so the useful guarantee is that
     * nothing else can ride along in the string — no markup, no URL, no second
     * colour smuggled in as an attribute.
     */
    fun isWellFormedPath(pathData: String?): Boolean {
        val value = pathData?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_PATH_LENGTH) return false
        if (value.first() != 'M' && value.first() != 'm') return false
        return value.all { it in PATH_COMMANDS || it in '0'..'9' || it in PATH_PUNCTUATION }
    }

    /**
     * Parse the `name|pathData` entries a plugin declares.
     *
     * One entry per glyph. The separator is a pipe because it cannot appear in
     * either half, so no escaping is needed and a malformed entry is always
     * detectable rather than silently splitting somewhere odd.
     */
    fun parse(entries: List<String>): ParseResult {
        val cleaned = entries.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size > MAX_GLYPHS_PER_PLUGIN) return ParseResult.Invalid(ERROR_TOO_MANY)

        val glyphs = mutableListOf<CustomGlyph>()
        val seen = mutableSetOf<String>()
        cleaned.forEach { entry ->
            val separator = entry.indexOf('|')
            if (separator <= 0) return ParseResult.Invalid(ERROR_INVALID_NAME)
            val name = entry.substring(0, separator).trim()
            val path = entry.substring(separator + 1).trim()
            if (!isWellFormedName(name)) return ParseResult.Invalid(ERROR_INVALID_NAME)
            if (!isWellFormedPath(path)) return ParseResult.Invalid(ERROR_INVALID_PATH)
            if (!seen.add(name)) return ParseResult.Invalid(ERROR_DUPLICATE_NAME)
            glyphs += CustomGlyph(name = name, pathData = path)
        }
        return ParseResult.Valid(glyphs)
    }

    private val PATH_COMMANDS = setOf(
        'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v',
        'C', 'c', 'S', 's', 'Q', 'q', 'T', 't',
        'A', 'a', 'Z', 'z',
    )

    // Digits are handled separately; 'e' and 'E' carry exponents in path data.
    private val PATH_PUNCTUATION = setOf('.', ',', '-', '+', ' ', '\t', '\n', '\r', 'e', 'E')
}
