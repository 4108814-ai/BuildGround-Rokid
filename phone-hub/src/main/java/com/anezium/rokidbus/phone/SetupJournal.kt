package com.anezium.rokidbus.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SetupJournalEntry(
    val atMillis: Long,
    val fromGlasses: Boolean,
    val code: String,
    val detail: String,
) {
    val isFailure: Boolean
        get() = FAILURE_HINTS.any { code.contains(it) }

    private companion object {
        val FAILURE_HINTS = listOf("fail", "refus", "stuck", "missing", "abandon", "redirect", "error")
    }
}

/**
 * What setup did, kept where an owner can actually read it.
 *
 * Everything interesting about a failing run lives in the lens's logcat, which nobody outside this
 * desk can reach. So both sides file short notes here instead: the phone writes what it asked for
 * and what came back, the glasses send the things only they can see -- a Settings list that would
 * not scroll, a screen that never appeared. It survives restarts, because an owner reports a
 * problem long after it happened, and it can be shared as plain text, because the only useful
 * answer to "it stopped working" is being able to read what it was doing at the time.
 *
 * Bounded on purpose. This is a trail, not telemetry.
 */
internal object SetupJournal {
    const val MAX_ENTRIES = 120
    private const val PREF_KEY = "setup_journal"

    fun record(context: Context, fromGlasses: Boolean, code: String, detail: String = "") {
        val cleanCode = code.trim().take(48)
        if (cleanCode.isEmpty()) return
        val entry = SetupJournalEntry(
            atMillis = System.currentTimeMillis(),
            fromGlasses = fromGlasses,
            code = cleanCode,
            // The diagnostic sanitiser already strips pairing codes and hosts; a journal that
            // leaks one into a shared text file would be worse than no journal.
            detail = ManualPairingSupportDiagnostic.sanitize(detail).take(160),
        )
        val kept = (entries(context) + entry).takeLast(MAX_ENTRIES)
        write(context, kept)
    }

    fun entries(context: Context): List<SetupJournalEntry> {
        val raw = prefs(context).getString(PREF_KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val code = item.optString("code").orEmpty()
                if (code.isBlank()) continue
                add(
                    SetupJournalEntry(
                        atMillis = item.optLong("at"),
                        fromGlasses = item.optBoolean("glasses"),
                        code = code,
                        detail = item.optString("detail").orEmpty(),
                    ),
                )
            }
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
    }

    private fun write(context: Context, entries: List<SetupJournalEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("at", entry.atMillis)
                    .put("glasses", entry.fromGlasses)
                    .put("code", entry.code)
                    .put("detail", entry.detail),
            )
        }
        prefs(context).edit().putString(PREF_KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
}

/** Turning the trail into something a human reads, kept pure so it can be tested without a device. */
internal object SetupJournalFormatter {
    fun line(entry: SetupJournalEntry, clock: (Long) -> String): String {
        val side = if (entry.fromGlasses) "glasses" else "phone"
        val detail = entry.detail.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        return "${clock(entry.atMillis)}  $side  ${entry.code}$detail"
    }

    /**
     * The shared text an owner sends when reporting a problem. Newest last, so it reads as a story
     * rather than a stack, and headed with the versions because the first question about any trail
     * is which build produced it.
     */
    fun shareText(
        entries: List<SetupJournalEntry>,
        phoneVersion: String,
        glassesVersion: String,
        clock: (Long) -> String,
    ): String = buildString {
        appendLine("Rokid Nexus setup log")
        appendLine("phone $phoneVersion · glasses ${glassesVersion.ifBlank { "unknown" }}")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("(nothing recorded yet)")
            return@buildString
        }
        entries.forEach { appendLine(line(it, clock)) }
    }
}
