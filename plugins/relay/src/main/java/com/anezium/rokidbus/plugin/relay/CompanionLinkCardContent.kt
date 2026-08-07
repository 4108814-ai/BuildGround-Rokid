package com.anezium.rokidbus.plugin.relay

import android.os.Build

internal object CompanionLinkCardContent {
    const val ANDROID_15_BODY =
        "Android blanks out any message with a code in it unless Relay is linked to your glasses as their " +
            "companion app. Linking also keeps Relay running when the system would otherwise stop it."
    const val LEGACY_BODY =
        "Linking Relay to your glasses as their companion app keeps it running when the system would " +
            "otherwise stop it."
    const val LINKED_BODY =
        "Linked. Relay is registered as your glasses' companion app, and is allowed to keep running."

    fun body(linked: Boolean, sdkInt: Int): String = when {
        linked -> LINKED_BODY
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ANDROID_15_BODY
        else -> LEGACY_BODY
    }
}
