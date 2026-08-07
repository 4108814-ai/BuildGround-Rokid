package com.anezium.rokidbus.plugin.relay

internal object SensitiveNotificationDetector {
    const val HIDDEN_BODY = "Hidden by Android — read it on your phone"
    const val ENGLISH_REDACTION_MESSAGE = "Sensitive notification content hidden"

    fun isRedacted(
        title: String?,
        text: String?,
        resolvedStrings: Collection<String>,
    ): Boolean {
        val markers = resolvedStrings
            .filter(String::isNotBlank)
            .toSet()
            .ifEmpty { setOf(ENGLISH_REDACTION_MESSAGE) }
        return listOfNotNull(
            title?.takeIf(String::isNotBlank),
            text?.takeIf(String::isNotBlank),
        ).any(markers::contains)
    }
}
