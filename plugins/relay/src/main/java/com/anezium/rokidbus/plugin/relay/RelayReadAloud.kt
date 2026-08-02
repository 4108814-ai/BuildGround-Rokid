package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.TtsContract

/** Pure selection and fitting for the one message a fresh notice reads aloud. */
internal object RelayReadAloud {
    fun textFor(
        enabled: Boolean,
        sender: String,
        renderedThread: String,
    ): String? {
        if (!enabled) return null
        val newest = RelayInboxCatalog.threadMessages(renderedThread).lastOrNull() ?: return null
        val speaker = newest.speaker.ifBlank { sender.trim() }
        val spoken = if (speaker.isBlank()) newest.text else "$speaker: ${newest.text}"
        return spoken
            .replace(LINE_BREAKS, " ")
            .trim()
            .takeIf(String::isNotBlank)
            ?.takeLast(TtsContract.MAX_TEXT_CHARS)
    }

    private val LINE_BREAKS = Regex("[\\r\\n]+")
}
