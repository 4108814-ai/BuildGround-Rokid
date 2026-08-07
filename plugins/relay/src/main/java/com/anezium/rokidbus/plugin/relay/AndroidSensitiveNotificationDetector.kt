package com.anezium.rokidbus.plugin.relay

import android.content.res.Resources
import android.os.Build

internal object AndroidSensitiveNotificationDetector {
    private val resolvedStrings by lazy(::resolveSystemStrings)

    fun isRedacted(title: String?, text: String?): Boolean {
        val systemStrings = resolvedStrings
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            systemStrings.isEmpty()
        ) {
            return false
        }
        return SensitiveNotificationDetector.isRedacted(title, text, systemStrings)
    }

    private fun resolveSystemStrings(): Set<String> {
        val resources = Resources.getSystem()
        return SYSTEM_STRING_NAMES.mapNotNullTo(linkedSetOf()) { name ->
            val id = resources.getIdentifier(name, "string", "android")
            if (id == 0) return@mapNotNullTo null
            runCatching { resources.getString(id) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }
    }

    private val SYSTEM_STRING_NAMES = listOf(
        "redacted_notification_message",
        "redacted_notification_action_title",
    )
}
