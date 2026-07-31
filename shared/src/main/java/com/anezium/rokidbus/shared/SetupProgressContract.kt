package com.anezium.rokidbus.shared

import java.util.Locale

object SetupStage {
    const val UNKNOWN = ""
    const val WAITING_FOR_ACCESSIBILITY = "waiting_for_accessibility"
    const val WAITING_FOR_WIFI = "waiting_for_wifi"
    const val ENABLING_DEVELOPER_OPTIONS = "enabling_developer_options"
    const val OPENING_WIRELESS_DEBUGGING = "opening_wireless_debugging"
    const val READING_PAIRING_DIALOG = "reading_pairing_dialog"
    const val PAIRING_LOCALLY = "pairing_locally"
    const val PAIRING_VIA_PHONE = "pairing_via_phone"
    const val ARMING = "arming"
    const val COMPLETE = "complete"
    const val MANUAL_REQUIRED = "manual_required"
    const val FAILED = "failed"

    val ALL: List<String> = listOf(
        UNKNOWN,
        WAITING_FOR_ACCESSIBILITY,
        WAITING_FOR_WIFI,
        ENABLING_DEVELOPER_OPTIONS,
        OPENING_WIRELESS_DEBUGGING,
        READING_PAIRING_DIALOG,
        PAIRING_LOCALLY,
        PAIRING_VIA_PHONE,
        ARMING,
        COMPLETE,
        MANUAL_REQUIRED,
        FAILED,
    )

    fun normalize(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.takeIf(ALL::contains).orEmpty()
    }

    fun isTerminal(stage: String): Boolean = normalize(stage) in setOf(
        COMPLETE,
        MANUAL_REQUIRED,
        FAILED,
    )

    fun requiresUserAction(stage: String): Boolean = normalize(stage) in setOf(
        WAITING_FOR_ACCESSIBILITY,
        WAITING_FOR_WIFI,
        MANUAL_REQUIRED,
        FAILED,
    )
}

object SetupCompletionMode {
    const val UNKNOWN = ""
    const val AUTOMATIC = "automatic"
    const val PHONE_ASSISTED = "phone_assisted"
    const val PHONE_MANUAL = "phone_manual"
    const val PM_GRANT = "pm_grant"

    val ALL: List<String> = listOf(
        UNKNOWN,
        AUTOMATIC,
        PHONE_ASSISTED,
        PHONE_MANUAL,
        PM_GRANT,
    )

    fun normalize(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.takeIf(ALL::contains).orEmpty()
    }
}
