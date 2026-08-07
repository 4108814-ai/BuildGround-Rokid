package com.anezium.rokidbus.plugin.relay

internal object CompanionLinkCardContent {
    const val ANDROID_15_BODY =
        "Android blanks out any message with a code in it unless Relay is linked to your glasses as their " +
            "companion app. Linking also keeps Relay running when the system would otherwise stop it."
    const val LEGACY_BODY =
        "Linking Relay to your glasses as their companion app keeps it running when the system would " +
            "otherwise stop it."
    const val LINKED_BODY =
        "Linked. Messages arrive whole, and Relay is allowed to keep running."

    fun body(linked: Boolean, sdkInt: Int): String = when {
        linked -> LINKED_BODY
        sdkInt >= 35 -> ANDROID_15_BODY
        else -> LEGACY_BODY
    }
}
