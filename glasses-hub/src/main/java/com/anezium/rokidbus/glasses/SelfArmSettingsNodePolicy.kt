package com.anezium.rokidbus.glasses

import java.util.Locale

internal object SelfArmSettingsNodePolicy {
    fun isActionTitle(
        visible: Boolean,
        enabled: Boolean,
        viewId: String?,
        className: String?,
        exactLabelMatch: Boolean,
        hasClickableAncestor: Boolean,
    ): Boolean {
        if (!visible || !enabled || !exactLabelMatch || !hasClickableAncestor) return false
        val id = viewId.orEmpty()
        val type = className.orEmpty()
        return id.endsWith(":id/title") ||
            id.endsWith("/title") ||
            (id.isBlank() && type.endsWith("TextView"))
    }

    fun isUsableToggle(
        visible: Boolean,
        enabled: Boolean,
        checkable: Boolean,
        clickable: Boolean,
    ): Boolean =
        visible && enabled && (checkable || clickable)

    fun isWifiScreen(
        settingsPackage: Boolean,
        openedByAutomator: Boolean,
        wifiActivityObserved: Boolean,
        hasStableToggleId: Boolean,
    ): Boolean =
        settingsPackage && openedByAutomator && wifiActivityObserved && hasStableToggleId

    fun isWifiActivityClass(className: String?): Boolean {
        val value = className.orEmpty().lowercase(Locale.ROOT)
        return value.endsWith("settings\$wifisettingsactivity") ||
            value.endsWith("settings.wifisettingsactivity")
    }

    fun isWifiToggleId(viewId: String?): Boolean = viewId in WIFI_TOGGLE_IDS

    private val WIFI_TOGGLE_IDS = setOf(
        "com.android.settings:id/main_switch_bar",
        "com.android.settings:id/switch_bar",
        "com.android.settings:id/switch_widget",
        "android:id/switch_widget",
        "com.android.settings:id/switch_text",
    )
}
